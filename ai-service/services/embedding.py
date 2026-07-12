"""Embedding 服务 — 文本向量化。

真实路径用 httpx 调用 OpenAI 兼容 Embedding 接口（兼容火山方舟）。
M3-3：取消静默降级（无 Key 不再造假向量），调用失败或向量维度不匹配时抛出
可识别的 :class:`ModelConfigError`，供 Java / 前端引导用户重新配置。
不引入 langchain-openai 重依赖，直接调用 /embeddings 接口，与 llm.py 的 httpx 风格一致。
"""

import hashlib
import json

import httpx
from loguru import logger

from core.config import settings
from core.redis_client import get_redis
from services.model_config import ModelConfig, ModelConfigError


class EmbeddingService:
    """文本向量化服务。"""

    def _model(self, cfg: ModelConfig) -> str:
        return cfg.embedding_model or settings.embedding_model

    def _cache_key(self, text: str, cfg: ModelConfig) -> str:
        """L2 缓存 key：embedding:{text_hash}:{model}:{key_hash}。

        包含模型名与 API Key 哈希，切换模型或 Key 自动失效旧缓存，避免跨配置串数据。
        """
        text_hash = hashlib.md5(text.encode("utf-8")).hexdigest()
        key_material = f"{self._model(cfg)}|{cfg.embedding_api_key or ''}"
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

    async def _embed_remote(
        self, texts: list[str], cfg: ModelConfig, client: httpx.AsyncClient | None = None
    ) -> list[list[float]]:
        """调用 OpenAI 兼容 Embedding 接口（批量）。

        调用失败（模型名 / API Key 错误）或返回维度与配置不符时抛出 :class:`ModelConfigError`。
        """
        base_url = cfg.embedding_base_url or settings.embedding_base_url
        model = self._model(cfg)
        try:
            if client is None:
                async with httpx.AsyncClient(timeout=60.0) as c:
                    resp = await c.post(
                        f"{base_url}/embeddings",
                        headers={
                            "Authorization": f"Bearer {cfg.embedding_api_key}",
                            "Content-Type": "application/json",
                        },
                        json={"model": model, "input": texts},
                    )
            else:
                resp = await client.post(
                    f"{base_url}/embeddings",
                    headers={
                        "Authorization": f"Bearer {cfg.embedding_api_key}",
                        "Content-Type": "application/json",
                    },
                    json={"model": model, "input": texts},
                )
            resp.raise_for_status()
            data = resp.json()["data"]
        except httpx.HTTPError as e:
            raise ModelConfigError(f"Embedding 调用失败（模型名或 API Key 可能错误）：{e}") from e

        data.sort(key=lambda d: d.get("index", 0))
        vecs = [d["embedding"] for d in data]
        dim = cfg.embedding_dimension
        if dim is not None and any(len(v) != dim for v in vecs):
            raise ModelConfigError(
                f"向量维度不匹配：配置维度 {dim}，实际模型返回维度 {len(vecs[0])}，"
                f"请在 AI 配置页核对 Embedding 维度"
            )
        return vecs


embedding_service = EmbeddingService()
