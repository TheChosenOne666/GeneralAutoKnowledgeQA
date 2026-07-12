"""RAG 检索服务 — 混合检索（向量 + BM25）+ RRF 融合 + 可选 Rerank 精排。

使用全局 vector_store_service（与文档处理为同一实例，内存数据一致）：
1. Query 向量化（Embedding）
2. 向量检索（Top-K=20）
3. BM25 关键词检索（Top-K=20）
4. RRF 融合去重
5. Rerank 精排（Top-N=5，未配置 rerank_api_key 则跳过）
"""

import hashlib
import json
from dataclasses import asdict

import httpx
from loguru import logger

from core.config import settings
from core.redis_client import get_redis
from services import vector_store as vector_store_module
from services.embedding import embedding_service
from services.model_config import ModelConfig, ModelConfigError
from services.vector_store import RetrievalResult


class RagService:
    """RAG 检索服务。"""

    @staticmethod
    def _cache_key(query: str, kb_ids: list[str], tenant_id: str) -> str:
        """L1 缓存 key：retrieval:{tenant_id}:{q_hash}（跨会话 tenant 级）。"""
        kb_part = ",".join(sorted(kb_ids)) if kb_ids else ""
        q_hash = hashlib.md5(f"{query}|{kb_part}".encode("utf-8")).hexdigest()
        return f"retrieval:{tenant_id}:{q_hash}"

    async def retrieve(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_n: int = 5,
        cfg: ModelConfig | None = None,
    ) -> list[RetrievalResult]:
        """完整检索流程：混合检索 → RRF 融合 → Rerank 精排。

        带 L1 检索结果缓存：相同 tenant + 问题（含知识库范围）命中时直接返回，
        跳过向量 / BM25 / RRF / Rerank，但仍交由 LLM 实时生成答案（不过时）。

        Args:
            query: 用户问题
            kb_ids: 知识库 ID 列表
            tenant_id: 租户 ID
            top_n: 最终返回的结果数

        Returns:
            精排后的检索结果列表
        """
        cache_key = self._cache_key(query, kb_ids, tenant_id)
        try:
            r = await get_redis()
            cached = await r.get(cache_key)
            if cached is not None:
                logger.info(f"L1 检索缓存命中: {cache_key}")
                return [RetrievalResult(**d) for d in json.loads(cached)]
        except Exception as e:
            logger.warning(f"读取检索缓存失败，降级实时检索: {e}")

        # 1. Query 向量化
        query_vec = await embedding_service.embed_text(query, cfg)

        # 2. 向量检索 Top-K=20
        vec_results = await vector_store_module.vector_store_service.search(
            query_vec, kb_ids, tenant_id, top_k=20
        )

        # 3. BM25 关键词检索 Top-K=20
        bm25_results = await vector_store_module.vector_store_service.keyword_search(
            query, kb_ids, tenant_id, top_k=20
        )

        # 相关性门槛所需信号：RRF 融合会覆盖原始分数，故先取向量/BM25 原始最高分
        best_vec = vec_results[0].score if vec_results else 0.0
        best_bm25 = bm25_results[0].score if bm25_results else 0.0

        # 4. RRF 融合去重
        merged = self._rrf([vec_results, bm25_results])

        # 5. Rerank 精排（未配置 rerank_api_key 则跳过）
        rerank_key = (cfg.rerank_api_key if cfg and cfg.rerank_api_key else None) or settings.rerank_api_key
        rerank_applied = False
        if rerank_key:
            merged, rerank_applied = await self._rerank(
                query,
                merged,
                top_n,
                rerank_key,
                (cfg.rerank_base_url if cfg else None) or settings.rerank_base_url,
                (cfg.rerank_model if cfg else None) or settings.rerank_model,
            )
        else:
            merged = merged[:top_n]

        # 6. 相关性门槛：检索结果全部不相关时视为「无相关文档」，返回空，
        #    避免下游（chat 路由）把不相关的错误引用来源展示给用户
        if settings.retrieval_relevance_gate and merged and not self._is_relevant(
            merged, rerank_applied, best_vec, best_bm25
        ):
            logger.info(
                f"检索相关性不足，视为无相关文档: query={query} "
                f"rerank_applied={rerank_applied} best_vec={best_vec:.3f} best_bm25={best_bm25:.3f}"
            )
            merged = []

        try:
            r = await get_redis()
            await r.set(
                cache_key,
                json.dumps([asdict(x) for x in merged], ensure_ascii=False),
                ex=settings.retrieval_cache_ttl,
            )
        except Exception as e:
            logger.warning(f"写入检索缓存失败，忽略: {e}")
        return merged

    async def _rerank(
        self,
        query: str,
        results: list[RetrievalResult],
        top_n: int = 5,
        rerank_api_key: str = "",
        rerank_base_url: str = "",
        rerank_model: str = "",
    ) -> tuple[list[RetrievalResult], bool]:
        """Rerank 精排（调用 OpenAI 兼容 Rerank 接口）。

        返回 ``(精排后结果, 是否真正应用 rerank 分数)``。调用失败（模型名 / API Key 错误）
        抛 :class:`ModelConfigError`，不静默降级。
        """
        if not results:
            return results, False
        documents = [r.content for r in results]
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.post(
                    f"{rerank_base_url}/rerank",
                    headers={
                        "Authorization": f"Bearer {rerank_api_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": rerank_model,
                        "query": query,
                        "documents": documents,
                        "top_n": top_n,
                    },
                )
                resp.raise_for_status()
                data = resp.json().get("results", [])
        except httpx.HTTPError as e:
            raise ModelConfigError(f"Rerank 调用失败（模型名或 API Key 可能错误）：{e}") from e
        ordered = []
        for item in data:
            idx = item.get("index")
            if idx is not None and 0 <= idx < len(results):
                r = results[idx]
                # 写入 rerank 相关性分数（兼容 relevance_score / score 两种字段名）
                r.score = float(item.get("relevance_score", item.get("score", r.score)))
                ordered.append(r)
        # 未返回可用结果则回退 RRF 顺序，并标记未应用 rerank 分数（改用向量/BM25 门槛）
        if not ordered:
            return results[:top_n], False
        return ordered, True

    @staticmethod
    def _is_relevant(
        merged: list[RetrievalResult],
        rerank_applied: bool,
        best_vec: float,
        best_bm25: float,
    ) -> bool:
        """检索结果是否相关（达到相关性门槛）。

        配置 rerank 且成功应用：以 rerank 相关性分数为准；否则以向量余弦最高分或
        BM25 最高分为准（两者满足其一即视为相关）。
        """
        if rerank_applied:
            return merged[0].score >= settings.retrieval_rerank_min_relevance
        return (
            best_vec >= settings.retrieval_vector_min_relevance
            or best_bm25 >= settings.retrieval_bm25_min_relevance
        )

    @staticmethod
    def _rrf(results_list: list[list[RetrievalResult]], k: int = 60) -> list[RetrievalResult]:
        """Reciprocal Rank Fusion：融合多路召回结果并去重。"""
        fused: dict[tuple, dict] = {}
        for results in results_list:
            for rank, r in enumerate(results):
                key = (r.doc_id, r.kb_id, r.chunk_index, r.content)
                entry = fused.setdefault(key, {"result": r, "score": 0.0})
                entry["score"] += 1.0 / (rank + k)
        items = sorted(fused.values(), key=lambda x: x["score"], reverse=True)
        for it in items:
            it["result"].score = round(it["score"], 6)
        return [it["result"] for it in items]


rag_service = RagService()
