"""Embedding 服务 — 文本向量化。

真实路径用 httpx 调用 OpenAI 兼容 Embedding 接口（兼容火山方舟）。
M3-3：取消静默降级（无 Key 不再造假向量），调用失败或向量维度不匹配时抛出
可识别的 :class:`ModelConfigError`，供 Java / 前端引导用户重新配置。
不引入 langchain-openai 重依赖，直接调用 /embeddings 接口，与 llm.py 的 httpx 风格一致。
"""

import asyncio
import hashlib
import json

import httpx
from loguru import logger

from core.config import settings
from core.redis_client import get_redis
from services.model_config import ModelConfig, ModelConfigError, ModelQuotaError, is_quota_error

# 支持 dimensions 参数的 Embedding 提供商白名单。
# 这些 OpenAI 兼容接口的 embedding 模型支持在请求中传入 dimensions 调整输出向量维度；
# 不在此列的提供商（如 BGE）不识别该参数，传入会导致 400，故不传、使用模型默认维度。
DIMENSION_SUPPORTED_PROVIDERS = {"火山方舟", "OpenAI", "阿里云百炼"}

# 单批 Embedding 请求的最大文本条数。
# 阿里云百炼等接口硬性限制每批 input 数量（如 text-embedding 模型 ≤ 20），
# 一次性发送整篇文档的全部 chunk 会触发 HTTP 400「batch size is invalid,
# it should not be larger than 20」。统一按此上限分批调用，对所有 OpenAI 兼容
# 提供商均安全（小限制模型不超限，大限制模型仅多几次请求）。
EMBEDDING_BATCH_SIZE = 20


class EmbeddingService:
    """文本向量化服务。"""

    def _model(self, cfg: ModelConfig) -> str:
        return cfg.embedding_model or settings.embedding_model

    def _cache_key(self, text: str, cfg: ModelConfig) -> str:
        """L2 缓存 key：embedding:{text_hash}:{model}:{key_hash}。

        包含模型名与 API Key 哈希，切换模型或 Key 自动失效旧缓存，避免跨配置串数据。
        """
        text_hash = hashlib.md5(text.encode("utf-8")).hexdigest()
        key_material = f"{self._model(cfg)}|{cfg.embedding_dimension}|{cfg.embedding_api_key or ''}"
        key_hash = hashlib.md5(key_material.encode("utf-8")).hexdigest()[:8]
        return f"embedding:{text_hash}:{key_hash}"

    async def embed_text(
        self, text: str, cfg: ModelConfig | None = None, client: httpx.AsyncClient | None = None
    ) -> list[float]:
        """将单段文本向量化（带 L2 Redis 缓存）。

        未配置 Embedding API Key 或调用失败（模型名/Key 错误）/ 维度不匹配时，
        抛出 :class:`ModelConfigError`。Redis 不可用时降级为直接计算（不缓存），不阻塞主流程。
        """
        cfg = cfg or ModelConfig.from_settings()
        # 未配置 API Key 时直接报错，绝不命中缓存返回（可能来自其他配置/降级的）伪向量。
        if not cfg.has_embedding():
            raise ModelConfigError("未配置 Embedding API Key，请在 AI 配置页填写后重试")

        cache_key = self._cache_key(text, cfg)
        try:
            r = await get_redis()
            cached = await r.get(cache_key)
            if cached is not None:
                return json.loads(cached)
        except Exception as e:
            logger.warning(f"读取嵌入缓存失败，降级直接计算: {e}")

        vec = (await self._embed_remote([text], cfg, client))[0]

        try:
            r = await get_redis()
            await r.set(cache_key, json.dumps(vec), ex=settings.embedding_cache_ttl)
        except Exception as e:
            logger.warning(f"写入嵌入缓存失败，忽略: {e}")
        return vec

    async def embed_batch(
        self,
        texts: list[str],
        cfg: ModelConfig | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> list[list[float]]:
        """批量向量化（逐条命中 L2 缓存，未命中的批量计算后回写）。"""
        if not texts:
            return []
        cfg = cfg or ModelConfig.from_settings()
        results: list[list[float] | None] = [None] * len(texts)
        miss_indices: list[int] = []
        try:
            r = await get_redis()
            for i, t in enumerate(texts):
                cached = await r.get(self._cache_key(t, cfg))
                if cached is not None:
                    results[i] = json.loads(cached)
                else:
                    miss_indices.append(i)
        except Exception as e:
            logger.warning(f"批量读嵌入缓存失败，降级全部重算: {e}")
            miss_indices = list(range(len(texts)))

        if not miss_indices:
            return [v for v in results if v is not None]

        if not cfg.has_embedding():
            raise ModelConfigError("未配置 Embedding API Key，请在 AI 配置页填写后重试")

        miss_vecs = await self._embed_remote([texts[i] for i in miss_indices], cfg, client)
        for pos, idx in enumerate(miss_indices):
            results[idx] = miss_vecs[pos]
        try:
            r = await get_redis()
            for idx in miss_indices:
                await r.set(
                    self._cache_key(texts[idx], cfg),
                    json.dumps(results[idx]),
                    ex=settings.embedding_cache_ttl,
                )
        except Exception as e:
            logger.warning(f"批量写嵌入缓存失败，忽略: {e}")
        return [v for v in results if v is not None]

    def _is_multimodal_embedding(self, model: str) -> bool:
        """判定是否为火山方舟多模态 Embedding 模型（doubao-embedding-vision 系列）。

        这类模型仅支持多模态专用端点 ``/embeddings/multimodal``，且 ``input`` 必须为对象数组
        ``[{"type": "text", "text": "..."}]``，不兼容标准 OpenAI ``/embeddings`` + 字符串数组。
        """
        return "embedding-vision" in (model or "").lower()

    async def _embed_remote(
        self, texts: list[str], cfg: ModelConfig, client: httpx.AsyncClient | None = None
    ) -> list[list[float]]:
        """调用 Embedding 接口（批量）。

        火山方舟多模态 Embedding 模型（doubao-embedding-vision 系列）走专用端点
        ``/embeddings/multimodal``，且 input 必须为单条文本对象 ``{"type": "text", "text": "..."}``
        （该端点对多条 input 会把各向量拼接成一维返回、难以拆分，故逐条调用）；
        其余模型走标准 OpenAI 兼容 ``/embeddings`` 且 input 为字符串数组。

        调用失败（模型名 / API Key 错误）或返回维度与配置不符时抛出 :class:`ModelConfigError`。
        """
        base_url = cfg.embedding_base_url or settings.embedding_base_url
        model = self._model(cfg)
        # 向量维度为必填：未填写（None）时直接报错，引导用户到 AI 配置页填写，
        # 绝不回退到默认值或静默使用模型默认维度。
        if not cfg.embedding_dimension:
            raise ModelConfigError("未配置 Embedding 向量维度，请在 AI 配置页填写向量维度后重试")
        multimodal = self._is_multimodal_embedding(model)
        # 按提供商白名单决定要不要透传 dimensions：仅白名单内提供商支持该参数，
        # 其余（如 BGE）不识别会 400，故不传、使用模型默认维度。
        pass_dimensions = (cfg.embedding_provider or "") in DIMENSION_SUPPORTED_PROVIDERS
        endpoint = (
            f"{base_url}/embeddings/multimodal" if multimodal else f"{base_url}/embeddings"
        )
        _emb_key = cfg.embedding_api_key or ""
        _emb_tail = "***" + _emb_key[-4:] if _emb_key else "null"
        logger.info(f"[M3-3诊断] Embedding请求 endpoint={endpoint} model={model} "
                    f"dimension={cfg.embedding_dimension} key尾4={_emb_tail}")
        headers = {
            "Authorization": f"Bearer {cfg.embedding_api_key}",
            "Content-Type": "application/json",
        }
        own_client = client is None
        if own_client:
            client = httpx.AsyncClient(timeout=60.0)
        try:

            async def _call(payload: dict) -> dict:
                """POST 一次 Embedding 请求。

                限流(429)/服务端(5xx) 瞬时错误退避重试（最多 3 次）；重试耗尽仍失败则抛
                :class:`ModelQuotaError`（额度 / 限流，前端提示稍后重试，而非「去配置」）。
                4xx（Key / 模型名错误）不重试：含额度 / 余额关键词的抛 :class:`ModelQuotaError`，
                否则抛 :class:`ModelConfigError`（配置错误，引导重配）。
                """
                last_err: Exception | None = None
                for attempt in range(3):
                    try:
                        resp = await client.post(endpoint, headers=headers, json=payload)
                        if resp.status_code == 429 or resp.status_code >= 500:
                            await asyncio.sleep(min(2 ** attempt, 8))
                            last_err = ModelQuotaError(
                                f"Embedding 调用被限流或暂时不可用（HTTP {resp.status_code}），"
                                f"可能是模型额度不足，请稍后重试或检查账户额度"
                            )
                            continue
                        resp.raise_for_status()
                        return resp.json()
                    except httpx.HTTPStatusError as e:
                        status = e.response.status_code
                        body = ""
                        try:
                            body = e.response.text
                        except Exception:
                            pass
                        if status == 429 or is_quota_error(body):
                            raise ModelQuotaError(
                                f"Embedding 调用被限流或额度不足（HTTP {status}），"
                                f"请稍后重试或检查账户额度"
                            ) from e
                        raise ModelConfigError(
                            f"Embedding 调用失败（API Key 或模型名可能错误，HTTP {status}）：{body[:200]}"
                        ) from e
                    except httpx.HTTPError as e:
                        if attempt == 2:
                            raise ModelConfigError(
                                f"Embedding 调用失败（网络 / 连接问题，可能是 API Key 或模型名错误）：{e}"
                            ) from e
                        await asyncio.sleep(min(2 ** attempt, 8))
                        last_err = e
                if last_err is not None:
                    raise last_err
                raise ModelQuotaError("Embedding 调用失败（未知错误，可能是额度 / 限流）")

            if multimodal:
                # 多模态端点单次仅支持单条 input，无法批量；原实现逐条串行调用，
                # 文档分块数多时（如 3000+ 块）整段向量化可达数分钟、且取消无法中断。
                # 改为受控并发（embedding_concurrency）替代串行，在限流退避下稳健推进。
                # dimensions 透传用户配置维度（火山方舟多模态 embedding 支持 1024/2048 可选）。
                sem = asyncio.Semaphore(settings.embedding_concurrency)

                async def _embed_one(t: str) -> list[float]:
                    async with sem:
                        payload = {"model": model, "input": [{"type": "text", "text": t}]}
                        if pass_dimensions:
                            payload["dimensions"] = cfg.embedding_dimension
                        body = await _call(payload)
                        return body["data"]["embedding"]

                vecs = list(await asyncio.gather(*(_embed_one(t) for t in texts)))
            else:
                # 标准 /embeddings：input 为字符串数组。阿里云百炼等接口对单批 input 数量
                # 有硬性上限（如 ≤ 20），一次性发送整篇文档的全部 chunk 会触发 HTTP 400
                # 「batch size is invalid, it should not be larger than 20」。
                # 故按 EMBEDDING_BATCH_SIZE 分批调用，再按原始顺序合并结果。
                all_vecs: list[list[float]] = []
                for start in range(0, len(texts), EMBEDDING_BATCH_SIZE):
                    batch = texts[start : start + EMBEDDING_BATCH_SIZE]
                    payload = {"model": model, "input": batch}
                    # 按提供商白名单决定要不要透传 dimensions：仅白名单内提供商（火山方舟/
                    # OpenAI/阿里云百炼）支持该参数，其余（如 BGE）不识别会 400，故不传、
                    # 使用模型默认维度。维度必填校验（入口）仍保留，用于结尾维度一致性校验。
                    if pass_dimensions:
                        payload["dimensions"] = cfg.embedding_dimension
                    body = await _call(payload)
                    data = body["data"]
                    # 单批内按 index 保序（部分提供商返回顺序不保证）
                    data.sort(key=lambda d: d.get("index", 0))
                    all_vecs.extend(d["embedding"] for d in data)
                vecs = all_vecs
        except httpx.HTTPError as e:
            raise ModelConfigError(
                f"Embedding 调用失败（模型名或 API Key 可能错误）：{e}"
            ) from e
        finally:
            if own_client:
                await client.aclose()
        dim = cfg.embedding_dimension
        if dim is not None and any(len(v) != dim for v in vecs):
            raise ModelConfigError(
                f"向量维度不匹配：配置维度 {dim}，实际模型返回维度 {len(vecs[0])}，"
                f"请在 AI 配置页核对 Embedding 维度"
            )
        return vecs


embedding_service = EmbeddingService()
