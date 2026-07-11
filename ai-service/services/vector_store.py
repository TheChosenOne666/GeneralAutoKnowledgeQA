"""向量存储服务 — 内存实现（默认，零外部依赖）+ Milvus 实现（可选集成）。

内存版：余弦相似度向量检索 + BM25 关键词检索，支持 tenant_id + kb_id 过滤，
保证开发 / 单测 / 演示全链路可立即跑通。

Milvus 版：基于 pymilvus 直接管理集合（已在 requirements 声明），lazy import，
需安装 pymilvus 并启动 Milvus 服务后通过 vector_store_type=milvus 启用。
"""

import math
import re
from dataclasses import dataclass

from core.config import settings


@dataclass
class RetrievalResult:
    """单条检索结果。"""

    content: str
    source: str
    page: int = 0
    score: float = 0.0
    doc_id: str = ""
    kb_id: str = ""
    chunk_index: int = 0


class _ChunkRecord:
    """内存存储中的单条分块记录。"""

    __slots__ = ("content", "embedding", "metadata")

    def __init__(self, content: str, embedding: list[float] | None, metadata: dict):
        self.content = content
        self.embedding = embedding
        self.metadata = metadata


class InMemoryVectorStore:
    """内存向量存储 — 余弦相似度检索 + BM25 关键词检索，支持租户/知识库过滤。"""

    def __init__(self):
        self._records: list[_ChunkRecord] = []

    async def store_chunks(
        self, chunks: list[dict], kb_id: str, doc_id: str, tenant_id: str
    ) -> None:
        """存储文档分块向量。chunks: [{content, metadata, embedding}]。"""
        for c in chunks:
            meta = dict(c.get("metadata") or {})
            meta.setdefault("doc_id", doc_id)
            meta.setdefault("kb_id", kb_id)
            meta.setdefault("tenant_id", tenant_id)
            self._records.append(_ChunkRecord(c["content"], c.get("embedding"), meta))

    async def search(
        self,
        query_vector: list[float],
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
    ) -> list[RetrievalResult]:
        """向量检索 Top-K（余弦相似度）。"""
        results = []
        for r in self._records:
            if r.metadata.get("tenant_id") != tenant_id:
                continue
            if kb_ids and r.metadata.get("kb_id") not in kb_ids:
                continue
            if not r.embedding or not query_vector:
                continue
            score = self._cosine(query_vector, r.embedding)
            results.append(self._to_result(r, score))
        results.sort(key=lambda x: x.score, reverse=True)
        return results[:top_k]

    async def keyword_search(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
    ) -> list[RetrievalResult]:
        """BM25 关键词检索 Top-K。"""
        candidates = [
            r
            for r in self._records
            if r.metadata.get("tenant_id") == tenant_id
            and (not kb_ids or r.metadata.get("kb_id") in kb_ids)
        ]
        if not candidates:
            return []
        def _bigrams(s: str) -> list[str]:
            s = s.lower().replace(" ", "")
            return [s[i : i + 2] for i in range(len(s) - 1)] or [s]

        tokenized = [_bigrams(r.content) for r in candidates]
        q_tokens = _bigrams(query)
        if not q_tokens:
            return []
        # IDF
        n = len(tokenized)
        idf = {}
        for t in set(q_tokens):
            df = sum(1 for toks in tokenized if t in toks)
            idf[t] = math.log((n - df + 0.5) / (df + 0.5) + 1)
        avgdl = sum(len(t) for t in tokenized) / n
        k1, b = 1.5, 0.75
        scored = []
        for r, toks in zip(candidates, tokenized):
            freq: dict[str, int] = {}
            for w in toks:
                freq[w] = freq.get(w, 0) + 1
            dl = len(toks)
            score = 0.0
            denom = 1 - b + b * (dl / avgdl if avgdl else 1)
            for qt in q_tokens:
                f = freq.get(qt, 0)
                if f:
                    score += idf.get(qt, 0.0) * (f * (k1 + 1)) / (f + k1 * denom)
            if score > 0:
                scored.append((score, r))
        scored.sort(key=lambda x: x[0], reverse=True)
        return [self._to_result(r, s) for s, r in scored[:top_k]]

    async def delete_by_doc(self, doc_id: str) -> None:
        """删除文档的所有向量。"""
        self._records = [r for r in self._records if r.metadata.get("doc_id") != doc_id]

    @staticmethod
    def _cosine(a: list[float], b: list[float]) -> float:
        if len(a) != len(b):
            return 0.0
        dot = sum(x * y for x, y in zip(a, b))
        na = math.sqrt(sum(x * x for x in a))
        nb = math.sqrt(sum(x * x for x in b))
        if na == 0 or nb == 0:
            return 0.0
        return dot / (na * nb)

    def _to_result(self, record: _ChunkRecord, score: float) -> RetrievalResult:
        m = record.metadata
        return RetrievalResult(
            content=record.content,
            source=m.get("source", ""),
            page=m.get("page", 0),
            score=score,
            doc_id=m.get("doc_id", ""),
            kb_id=m.get("kb_id", ""),
            chunk_index=m.get("chunk_index", 0),
        )


class MilvusVectorStore:
    """Milvus 向量存储 — 基于 pymilvus 直接管理（可选集成，lazy import）。"""

    def __init__(self):
        try:
            import pymilvus  # noqa: F401
        except ImportError as e:
            raise RuntimeError(
                "Milvus 集成未启用：请先 `pip install pymilvus` 并启动 Milvus 服务"
            ) from e
        self._host = settings.milvus_host
        self._port = settings.milvus_port
        self._default_dim = settings.embedding_dimension

    def _connect(self) -> None:
        from pymilvus import connections

        connections.connect(alias="default", host=self._host, port=self._port)

    def _collection_name(self, tenant_id: str) -> str:
        return f"xiongda_{tenant_id}"

    def _ensure_collection(self, tenant_id: str, dim: int):
        from pymilvus import (
            Collection,
            CollectionSchema,
            DataType,
            FieldSchema,
            utility,
        )

        name = self._collection_name(tenant_id)
        if utility.has_collection(name):
            return Collection(name)
        fields = [
            FieldSchema("pk", DataType.INT64, is_primary=True, auto_id=True),
            FieldSchema("content", DataType.VARCHAR, max_length=65535),
            FieldSchema("embedding", DataType.FLOAT_VECTOR, dim=dim),
            FieldSchema("doc_id", DataType.VARCHAR, max_length=64),
            FieldSchema("kb_id", DataType.VARCHAR, max_length=64),
            FieldSchema("tenant_id", DataType.VARCHAR, max_length=64),
            FieldSchema("source", DataType.VARCHAR, max_length=512),
            FieldSchema("page", DataType.INT64),
            FieldSchema("chunk_index", DataType.INT64),
        ]
        schema = CollectionSchema(fields, description="xiongda chunks")
        col = Collection(name, schema)
        col.create_index(
            "embedding",
            {
                "index_type": "IVF_FLAT",
                "metric_type": "COSINE",
                "params": {"nlist": 128},
            },
        )
        return col

    async def store_chunks(
        self, chunks: list[dict], kb_id: str, doc_id: str, tenant_id: str
    ) -> None:
        self._connect()
        dim = self._default_dim
        for c in chunks:
            if c.get("embedding"):
                dim = len(c["embedding"])
                break
        col = self._ensure_collection(tenant_id, dim)
        entities = []
        for c in chunks:
            meta = dict(c.get("metadata") or {})
            meta.setdefault("doc_id", doc_id)
            meta.setdefault("kb_id", kb_id)
            meta.setdefault("tenant_id", tenant_id)
            entities.append(
                {
                    "content": c["content"],
                    "embedding": c.get("embedding") or [0.0] * dim,
                    "doc_id": meta.get("doc_id", ""),
                    "kb_id": meta.get("kb_id", ""),
                    "tenant_id": meta.get("tenant_id", ""),
                    "source": meta.get("source", ""),
                    "page": meta.get("page", 0),
                    "chunk_index": meta.get("chunk_index", 0),
                }
            )
        col.insert(entities)
        col.flush()

    async def search(
        self,
        query_vector: list[float],
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
    ) -> list[RetrievalResult]:
        self._connect()
        from pymilvus import Collection

        col = self._ensure_collection(tenant_id, len(query_vector))
        col.load()
        expr = f'tenant_id == "{tenant_id}"'
        if kb_ids:
            kb_list = ", ".join(f'"{k}"' for k in kb_ids)
            expr += f" and kb_id in [{kb_list}]"
        hits = col.search(
            data=[query_vector],
            anns_field="embedding",
            param={"metric_type": "COSINE", "params": {"nprobe": 10}},
            limit=top_k,
            expr=expr,
        )
        results = []
        for hit in hits[0]:
            e = hit.entity
            results.append(
                RetrievalResult(
                    content=e.get("content", ""),
                    source=e.get("source", ""),
                    page=e.get("page", 0),
                    score=hit.score,
                    doc_id=e.get("doc_id", ""),
                    kb_id=e.get("kb_id", ""),
                    chunk_index=e.get("chunk_index", 0),
                )
            )
        return results

    async def keyword_search(
        self, query: str, kb_ids: list[str], tenant_id: str, top_k: int = 20
    ) -> list[RetrievalResult]:
        # Milvus 版 BM25 暂未实现，由向量检索覆盖（本地演示用内存版更完整）
        return []

    async def delete_by_doc(self, doc_id: str) -> None:
        self._connect()
        from pymilvus import Collection, utility

        for name in utility.list_collections():
            if name.startswith("xiongda_"):
                Collection(name).delete(f'doc_id == "{doc_id}"')


def _build_store():
    """根据配置构建向量存储实例（单例）。"""
    if settings.vector_store_type == "milvus":
        return MilvusVectorStore()
    return InMemoryVectorStore()


vector_store_service = _build_store()
