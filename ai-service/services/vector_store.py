"""向量存储服务 — Milvus 检索。

TODO: 接入 LangChain Milvus VectorStore。
"""

from dataclasses import dataclass


@dataclass
class RetrievalResult:
    """单条检索结果。"""

    content: str
    source: str
    page: int = 0
    score: float = 0.0


class VectorStoreService:
    """向量数据库服务（骨架）。"""

    async def search(
        self,
        query_vector: list[float],
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
    ) -> list[RetrievalResult]:
        """向量检索 Top-K。"""
        # TODO:
        # from langchain_milvus import Milvus
        # store = Milvus(connection_args={"host": ..., "port": ...})
        # docs = store.similarity_search_by_vector(query_vector, k=top_k, filter=...)
        raise NotImplementedError

    async def store_chunks(
        self,
        chunks: list[dict],
        kb_id: str,
        doc_id: str,
        tenant_id: str,
    ) -> None:
        """存储文档分块向量。"""
        # TODO: Milvus insert
        raise NotImplementedError

    async def delete_by_doc(self, doc_id: str) -> None:
        """删除文档的所有向量。"""
        # TODO: Milvus delete
        raise NotImplementedError


vector_store_service = VectorStoreService()
