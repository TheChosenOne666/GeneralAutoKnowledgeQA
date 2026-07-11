"""RAG 检索服务 — 混合检索 + Rerank 精排。

使用 LangChain 编排检索流程：
1. Query 向量化（Embedding）
2. 向量检索（Milvus Top-K=20）
3. BM25 关键词检索（Top-K=20）
4. 合并去重
5. Rerank 精排（Top-N=5）
"""

from dataclasses import dataclass

from services.embedding import embedding_service
from services.vector_store import VectorStoreService, RetrievalResult


class RagService:
    """RAG 检索服务（骨架）。"""

    def __init__(self):
        self.vector_store = VectorStoreService()

    async def retrieve(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_n: int = 5,
    ) -> list[RetrievalResult]:
        """完整检索流程：混合检索 → Rerank 精排。

        Args:
            query: 用户问题
            kb_ids: 知识库 ID 列表
            tenant_id: 租户 ID
            top_n: 最终返回的结果数

        Returns:
            精排后的检索结果列表
        """
        # TODO: 完整流程
        # 1. query_vec = await embedding_service.embed_text(query)
        # 2. vec_results = await self.vector_store.search(query_vec, kb_ids, tenant_id)
        # 3. bm25_results = await self._bm25_search(query, kb_ids, tenant_id)
        # 4. merged = self._merge(vec_results, bm25_results)
        # 5. reranked = await self._rerank(query, merged, top_n)
        # return reranked

        # 骨架阶段返回空
        return []

    async def _bm25_search(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
    ) -> list[RetrievalResult]:
        """BM25 关键词检索。"""
        # TODO: 接入 Elasticsearch 或 PostgreSQL 全文检索
        raise NotImplementedError

    async def _rerank(
        self,
        query: str,
        results: list[RetrievalResult],
        top_n: int = 5,
    ) -> list[RetrievalResult]:
        """Rerank 精排。"""
        # TODO: 调用 Rerank API
        raise NotImplementedError


rag_service = RagService()
