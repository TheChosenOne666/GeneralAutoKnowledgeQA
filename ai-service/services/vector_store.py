"""向量存储服务 — 三种实现：

- PgVectorStore（默认）：向量持久化到 Postgres（pgvector halfvec，变维度），
  关键词检索复用 BM25，BM25 索引在启动时从 PG 重建，重启不丢知识。
  对齐业界成熟 RAG 方案 的 Postgres 向量存储方案。
- InMemoryVectorStore：内存实现（零外部依赖），用于单测 / 演示。
- MilvusVectorStore：可选集成（pymilvus）。
"""

import json
import math
import re
from dataclasses import dataclass

from loguru import logger

from core.config import settings
from core.pg_client import get_pg_pool


# 增强块类型（由 LLM 生成，非原文）：重建原始块/预览时必须排除，否则增强 worker
# 重建时把上一轮增强块当原文再生成，导致无限膨胀。含 qa（M4-8）与 M5-7 的
# question/summary/wiki/entity。parent 父块单独排除（内容与子块重合）。
AUGMENT_CHUNK_TYPES = ("qa", "question", "summary", "wiki", "entity")


def _exclude_chunk_types_sql(types: tuple[str, ...]) -> str:
    """生成 PG SQL 片段，排除指定 chunk_type（用 IS DISTINCT FROM 兼容 NULL 原文块）。"""
    return "\n                     ".join(
        f"AND (metadata->>'chunk_type' IS DISTINCT FROM '{t}')" for t in types
    )


@dataclass
class RetrievalResult:
    """单条检索结果。

    M5-5 父子分块：``parent_id`` 为命中子块归属的父块 id；``parent_content`` 为回溯到的
    父块完整内容（供 LLM 上下文使用，small-to-big），检索时由 :func:`attach_parent_contents`
    填充。引用来源展示仍用 ``content``（精确定位命中片段）。
    """

    content: str
    source: str
    page: int = 0
    score: float = 0.0
    doc_id: str = ""
    kb_id: str = ""
    chunk_index: int = 0
    chunk_type: str = ""
    parent_id: str = ""
    parent_content: str = ""


class _ChunkRecord:
    """内存存储中的单条分块记录（BM25 兜底使用）。"""

    __slots__ = ("content", "embedding", "metadata")

    def __init__(self, content: str, embedding: list[float] | None, metadata: dict):
        self.content = content
        self.embedding = embedding
        self.metadata = metadata


def _bigrams(s: str) -> list[str]:
    """将文本转成字符 bigram（中文按字、英文按词处理都可用）。"""
    s = s.lower().replace(" ", "")
    return [s[i : i + 2] for i in range(len(s) - 1)] or [s]


def _aggregate_pages(
    records: list, content_getter, page_getter
) -> list[dict]:
    """按 page 升序聚合分块文本，返回 ``[{"page_no": int, "text": str}, ...]``。

    Args:
        records: 分块列表（内存记录或 dict）。
        content_getter: 取分块文本的函数。
        page_getter: 取分块页码的函数。
    """
    pages: dict[int, list[str]] = {}
    for r in records:
        p = page_getter(r)
        pages.setdefault(p, []).append(content_getter(r))
    return [{"page_no": p, "text": "\n".join(pages[p])} for p in sorted(pages)]


def _bm25_search(records: list[_ChunkRecord], query: str, top_k: int = 20) -> list[RetrievalResult]:
    """BM25 关键词检索（共享实现，内存版与 PG 版均复用）。

    Args:
        records: 已按租户 / 知识库过滤后的候选分块。
        query: 查询文本。
        top_k: 返回条数。
    """
    if not records:
        return []
    tokenized = [_bigrams(r.content) for r in records]
    q_tokens = _bigrams(query)
    if not q_tokens:
        return []

    # IDF
    n = len(tokenized)
    idf: dict[str, float] = {}
    for t in set(q_tokens):
        df = sum(1 for toks in tokenized if t in toks)
        idf[t] = math.log((n - df + 0.5) / (df + 0.5) + 1)
    avgdl = sum(len(t) for t in tokenized) / n
    k1, b = 1.5, 0.75
    scored: list[tuple[float, _ChunkRecord]] = []
    for r, toks in zip(records, tokenized):
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
    return [
            RetrievalResult(
            content=r.content,
            source=r.metadata.get("source", ""),
            page=r.metadata.get("page", 0),
            score=s,
            doc_id=r.metadata.get("doc_id", ""),
            kb_id=r.metadata.get("kb_id", ""),
            chunk_index=r.metadata.get("chunk_index", 0),
            chunk_type=r.metadata.get("chunk_type", ""),
            parent_id=r.metadata.get("parent_id", "") or "",
        )
        for s, r in scored[:top_k]
    ]


async def attach_parent_contents(store, results: list[RetrievalResult]) -> list[RetrievalResult]:
    """M5-5：为命中子块回溯并填充其父块完整内容（small-to-big）。

    收集结果中出现的 ``(doc_id, parent_id)``，按文档批量读回父块内容后填入
    ``RetrievalResult.parent_content``；无父块归属或父块缺失的结果保持 ``parent_content=''``。
    职责置于 vector_store 模块，供 :mod:`services.rag` 在检索末尾统一调用。

    Args:
        store: 向量存储实例（需实现 :meth:`get_parents_by_doc`）。
        results: 待回溯的检索结果列表（原地填充并返回）。
    """
    doc_ids = {r.doc_id for r in results if r.parent_id and r.doc_id}
    if not doc_ids:
        return results
    parent_map: dict[tuple, str] = {}
    for doc_id in doc_ids:
        try:
            parents = await store.get_parents_by_doc(doc_id)
        except Exception as e:  # noqa: BLE001 - 回溯为增强项，失败不应中断检索
            logger.warning(f"[父子分块] 读回父块失败 doc_id={doc_id}: {e}")
            continue
        for p in parents:
            parent_map[(doc_id, str(p.get("parent_id", "")))] = p.get("content", "")
    for r in results:
        if r.parent_id:
            r.parent_content = parent_map.get((r.doc_id, str(r.parent_id)), "")
    return results


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
        chunk_types: list[str] | None = None,
    ) -> list[RetrievalResult]:
        """向量检索 Top-K（余弦相似度）。

        Args:
            chunk_types: 仅返回该列表内的 ``chunk_type``；为 None 不限制。
        """
        results = []
        for r in self._records:
            if r.metadata.get("tenant_id") != tenant_id:
                continue
            if kb_ids and r.metadata.get("kb_id") not in kb_ids:
                continue
            # 常规检索默认排除 outline 块，大纲意图显式传 ["outline"]
            if chunk_types is None:
                if r.metadata.get("chunk_type") == "outline":
                    continue
            elif r.metadata.get("chunk_type") not in chunk_types:
                continue
            if not r.embedding or not query_vector:
                continue
            score = self._cosine(query_vector, r.embedding)
            results.append(
                RetrievalResult(
                    content=r.content,
                    source=r.metadata.get("source", ""),
                    page=r.metadata.get("page", 0),
                    score=score,
                    doc_id=r.metadata.get("doc_id", ""),
                    kb_id=r.metadata.get("kb_id", ""),
                    chunk_index=r.metadata.get("chunk_index", 0),
                    chunk_type=r.metadata.get("chunk_type", ""),
                    parent_id=r.metadata.get("parent_id", "") or "",
                )
            )
        results.sort(key=lambda x: x.score, reverse=True)
        return results[:top_k]

    async def keyword_search(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
        chunk_types: list[str] | None = None,
    ) -> list[RetrievalResult]:
        """BM25 关键词检索 Top-K。

        Args:
            chunk_types: 仅返回该列表内的 ``chunk_type``；为 None 不限制。
        """
        # 常规检索默认排除 outline 与 parent 块（chunk_types=None），大纲意图显式传 ["outline"]；
        # parent 父块仅供回溯上下文，任何情况都不进关键词检索。
        def _type_ok(meta):
            ct = meta.get("chunk_type")
            if ct == "parent":
                return False
            return ct != "outline" if chunk_types is None else ct in chunk_types
        candidates = [
            r
            for r in self._records
            if r.metadata.get("tenant_id") == tenant_id
            and (not kb_ids or r.metadata.get("kb_id") in kb_ids)
            and _type_ok(r.metadata)
        ]
        return _bm25_search(candidates, query, top_k)

    async def delete_by_doc(self, doc_id: str) -> None:
        """删除文档的所有向量。"""
        self._records = [r for r in self._records if r.metadata.get("doc_id") != doc_id]

    async def get_original_chunks(self, doc_id: str) -> list[dict]:
        """读回文档的原始子块（排除 qa 增强块与 parent 父块），供增强 worker 重建。"""
        return [
            {
                "content": r.content,
                "chunk_index": r.metadata.get("chunk_index", 0),
                "page": r.metadata.get("page", 0),
                "source": r.metadata.get("source", ""),
            }
            for r in self._records
            if r.metadata.get("doc_id") == doc_id
            and r.metadata.get("chunk_type") not in AUGMENT_CHUNK_TYPES + ("parent",)
        ]

    async def get_parents_by_doc(self, doc_id: str) -> list[dict]:
        """读回文档的所有父块（M5-5），供检索回溯与增强重建透传。"""
        return [
            {
                "content": r.content,
                "parent_id": r.metadata.get("parent_id", "") or "",
                "chunk_index": r.metadata.get("chunk_index", 0),
                "page": r.metadata.get("page", 0),
                "source": r.metadata.get("source", ""),
            }
            for r in self._records
            if r.metadata.get("doc_id") == doc_id
            and r.metadata.get("chunk_type") == "parent"
        ]

    async def attach_parents(self, results: list[RetrievalResult]) -> list[RetrievalResult]:
        """M5-5：为命中子块回溯并填充父块内容（small-to-big）。"""
        return await attach_parent_contents(self, results)

    async def get_document_pages(self, doc_id: str) -> list[dict]:
        """从内存分块按页重建文档文本（预览兜底，不依赖原文件是否存在）。

        排除增强块（qa/question/summary/wiki/entity）与 parent 父块（父块内容与子块
        重合，纳入会导致预览重复）。按 page 升序聚合，返回
        ``[{"page_no": int, "text": str}, ...]``。
        """
        chunks = [
            r
            for r in self._records
            if str(r.metadata.get("doc_id", "")) == str(doc_id)
            and r.metadata.get("chunk_type") not in AUGMENT_CHUNK_TYPES + ("parent",)
        ]
        chunks.sort(
            key=lambda r: (r.metadata.get("page", 0) or 0, r.metadata.get("chunk_index", 0) or 0)
        )
        return _aggregate_pages(chunks, lambda r: r.content, lambda r: r.metadata.get("page", 0) or 0)

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


class PgVectorStore:
    """Postgres（pgvector）向量存储 — 持久化，重启不丢知识。

    - 向量检索：走 PG（halfvec 余弦距离），支持 tenant_id / kb_id 过滤。
    - 关键词检索：复用 BM25，BM25 索引在 ``warmup_bm25`` 时从 PG 全量加载，
      落库 / 删除时同步更新，保证重启后兜底检索仍可用。
    """

    def __init__(self):
        self._bm25_records: list[_ChunkRecord] = []

    async def store_chunks(
        self, chunks: list[dict], kb_id: str, doc_id: str, tenant_id: str
    ) -> None:
        """持久化文档分块向量到 PG（同 doc_id 幂等覆盖），并同步 BM25 索引。"""
        rows = []
        new_records = []
        for c in chunks:
            meta = dict(c.get("metadata") or {})
            meta.setdefault("doc_id", doc_id)
            meta.setdefault("kb_id", kb_id)
            meta.setdefault("tenant_id", tenant_id)
            emb = c.get("embedding")
            dim = len(emb) if emb else 0
            rows.append(
                (
                    tenant_id,
                    kb_id,
                    doc_id,
                    int(meta.get("chunk_index", 0)),
                    meta.get("source", ""),
                    int(meta.get("page", 0) or 0),
                    c["content"],
                    dim,
                    _to_halfvec(emb),
                    json.dumps(meta, ensure_ascii=False),
                )
            )
            # M5-5：父块仅供回溯上下文，不进 BM25 关键词索引（父块很长会干扰关键词召回）
            if meta.get("chunk_type") != "parent":
                new_records.append(_ChunkRecord(c["content"], emb, meta))

        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            # 幂等：先清同 doc_id 旧分块，再插入（重处理不产生重复）
            await conn.execute("DELETE FROM embeddings WHERE doc_id = $1", doc_id)
            await conn.executemany(
                """INSERT INTO embeddings
                       (tenant_id, kb_id, doc_id, chunk_index, source, page, content, dimension, embedding, metadata)
                   VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::halfvec, $10::jsonb)""",
                rows,
            )

        # 同步 BM25 索引（清旧 + 加新，保持与 PG 一致）
        self._bm25_records = [
            r for r in self._bm25_records if r.metadata.get("doc_id") != doc_id
        ]
        self._bm25_records.extend(new_records)
        logger.info(f"[PgVectorStore] 落库 doc_id={doc_id} 分块数={len(rows)}")

    async def search(
        self,
        query_vector: list[float],
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
        chunk_types: list[str] | None = None,
    ) -> list[RetrievalResult]:
        """向量检索 Top-K（PG halfvec 余弦相似度，score = 1 - cosine_distance）。

        Args:
            chunk_types: 仅返回该列表内的 ``chunk_type``（如 ``["outline"]`` 用于大纲
                意图召回）；为 None 时不限制。受限于 SQL 分支，指定时放大候选池到
                ``max(top_k, 60)`` 再在内存过滤，保证足够召回。
        """
        if not query_vector:
            return []
        dim = len(query_vector)
        qv = _to_halfvec(query_vector)
        eff_top = top_k if chunk_types is None else max(top_k, 60)
        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            if kb_ids:
                rows = await conn.fetch(
                    """SELECT content, source, page, doc_id, kb_id, chunk_index,
                              metadata->>'chunk_type' AS chunk_type,
                              metadata->>'parent_id' AS parent_id,
                              1 - (embedding <=> $1::halfvec) AS score
                       FROM embeddings
                       WHERE tenant_id = $2 AND dimension = $3 AND kb_id = ANY($4)
                       ORDER BY embedding <=> $1::halfvec
                       LIMIT $5""",
                    qv,
                    tenant_id,
                    dim,
                    list(kb_ids),
                    eff_top,
                )
            else:
                rows = await conn.fetch(
                    """SELECT content, source, page, doc_id, kb_id, chunk_index,
                              metadata->>'chunk_type' AS chunk_type,
                              metadata->>'parent_id' AS parent_id,
                              1 - (embedding <=> $1::halfvec) AS score
                       FROM embeddings
                       WHERE tenant_id = $2 AND dimension = $3
                       ORDER BY embedding <=> $1::halfvec
                       LIMIT $4""",
                    qv,
                    tenant_id,
                    dim,
                    eff_top,
                )
        results = [
            RetrievalResult(
                content=r["content"],
                source=r["source"] or "",
                page=r["page"] or 0,
                score=float(r["score"]),
                doc_id=r["doc_id"],
                kb_id=r["kb_id"],
                chunk_index=r["chunk_index"],
                chunk_type=r["chunk_type"] or "",
                parent_id=r["parent_id"] or "",
            )
            for r in rows
        ]
        # 常规检索（chunk_types=None）默认排除 outline 块，避免稀释常规问答召回；
        # 大纲意图检索显式传 ["outline"] 才只取大纲块。
        if chunk_types is None:
            results = [r for r in results if r.chunk_type != "outline"]
        else:
            results = [r for r in results if r.chunk_type in chunk_types]
        return results[:top_k]

    async def keyword_search(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
        chunk_types: list[str] | None = None,
    ) -> list[RetrievalResult]:
        """BM25 关键词检索（兜底）—— 基于从 PG 预热的内存索引。

        Args:
            chunk_types: 仅返回该列表内的 ``chunk_type``；为 None 不限制。
        """
        # 常规检索默认排除 outline 与 parent 块（chunk_types=None），大纲意图显式传 ["outline"]；
        # parent 父块仅供回溯上下文，任何情况都不进关键词检索。
        def _type_ok(meta):
            ct = meta.get("chunk_type")
            if ct == "parent":
                return False
            return ct != "outline" if chunk_types is None else ct in chunk_types
        candidates = [
            r
            for r in self._bm25_records
            if r.metadata.get("tenant_id") == tenant_id
            and (not kb_ids or r.metadata.get("kb_id") in kb_ids)
            and _type_ok(r.metadata)
        ]
        return _bm25_search(candidates, query, top_k)

    async def delete_by_doc(self, doc_id: str) -> None:
        """从 PG 与 BM25 索引中删除文档的所有向量。"""
        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM embeddings WHERE doc_id = $1", doc_id)
        self._bm25_records = [
            r for r in self._bm25_records if r.metadata.get("doc_id") != doc_id
        ]
        logger.info(f"[PgVectorStore] 删除向量 doc_id={doc_id}")

    async def get_original_chunks(self, doc_id: str) -> list[dict]:
        """读回文档的原始子块（排除增强块 qa/question/summary/wiki/entity 与 parent 父块），供增强 worker 重建（不依赖上传文件是否仍在）。

        Returns:
            [{"content", "chunk_index", "page", "source"}]；文档已删则返回空列表。
        """
        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                f"""SELECT content, chunk_index, page, source
                   FROM embeddings
                   WHERE doc_id = $1
                     {_exclude_chunk_types_sql(AUGMENT_CHUNK_TYPES + ("parent",))}
                   ORDER BY chunk_index""",
                doc_id,
            )
        return [
            {
                "content": r["content"],
                "chunk_index": r["chunk_index"] or 0,
                "page": r["page"] or 0,
                "source": r["source"] or "",
            }
            for r in rows
        ]

    async def get_parents_by_doc(self, doc_id: str) -> list[dict]:
        """读回文档的所有父块（M5-5），供检索回溯与增强重建透传。

        Returns:
            [{"content", "parent_id", "chunk_index", "page", "source"}]。
        """
        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                """SELECT content, metadata->>'parent_id' AS parent_id, chunk_index, page, source
                   FROM embeddings
                   WHERE doc_id = $1 AND metadata->>'chunk_type' = 'parent'
                   ORDER BY chunk_index""",
                doc_id,
            )
        return [
            {
                "content": r["content"],
                "parent_id": r["parent_id"] or "",
                "chunk_index": r["chunk_index"] or 0,
                "page": r["page"] or 0,
                "source": r["source"] or "",
            }
            for r in rows
        ]

    async def attach_parents(self, results: list[RetrievalResult]) -> list[RetrievalResult]:
        """M5-5：为命中子块回溯并填充父块内容（small-to-big）。"""
        return await attach_parent_contents(self, results)

    async def get_document_pages(self, doc_id: str) -> list[dict]:
        """从 PG 向量库按页重建文档文本（预览兜底，不依赖原文件是否存在）。

        排除增强块（qa/question/summary/wiki/entity）与 parent 父块（与
        :meth:`get_original_chunks` 一致；父块内容与子块重合，纳入会导致预览重复）。
        按 page、chunk_index 升序聚合，返回 ``[{"page_no": int, "text": str}, ...]``。
        """
        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                f"""SELECT page, content, chunk_index
                   FROM embeddings
                   WHERE doc_id = $1
                     {_exclude_chunk_types_sql(AUGMENT_CHUNK_TYPES + ("parent",))}
                   ORDER BY page, chunk_index""",
                str(doc_id),
            )
        chunks = [{"content": r["content"], "page": r["page"] or 0} for r in rows]
        return _aggregate_pages(chunks, lambda c: c["content"], lambda c: c["page"])

    async def warmup_bm25(self) -> None:
        """从 PG 全量加载分块到内存 BM25 索引（应用启动时调用，重启不丢兜底检索）。

        M5-5：排除 parent 父块（父块仅供回溯上下文，不进关键词检索）；同时把
        ``chunk_type`` / ``parent_id`` 一并载入 metadata，使重启后 outline 排除与父块
        回溯归属仍然正确（此前 warmup 丢失 chunk_type 会导致重启后 outline 块被误召回）。
        """
        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                """SELECT content, source, page, doc_id, kb_id, chunk_index, tenant_id,
                          metadata->>'chunk_type' AS chunk_type,
                          metadata->>'parent_id' AS parent_id
                   FROM embeddings
                   WHERE metadata->>'chunk_type' IS DISTINCT FROM 'parent'"""
            )
        self._bm25_records = [
            _ChunkRecord(
                r["content"],
                None,
                {
                    "content": r["content"],
                    "source": r["source"],
                    "page": r["page"],
                    "doc_id": r["doc_id"],
                    "kb_id": r["kb_id"],
                    "chunk_index": r["chunk_index"],
                    "tenant_id": r["tenant_id"],
                    "chunk_type": r["chunk_type"] or "",
                    "parent_id": r["parent_id"] or "",
                },
            )
            for r in rows
        ]
        logger.info(f"[PgVectorStore] BM25 预热完成，加载 {len(self._bm25_records)} 条分块")


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

    async def get_original_chunks(self, doc_id: str) -> list[dict]:
        # Milvus 当前未启用，且元数据未落库，无法区分 qa 块；返回空（不影响 pgvector 主路径）
        return []

    async def get_parents_by_doc(self, doc_id: str) -> list[dict]:
        # Milvus 当前未启用，父块元数据未落库；返回空（不影响 pgvector 主路径）
        return []

    async def attach_parents(self, results: list[RetrievalResult]) -> list[RetrievalResult]:
        return results


def _to_halfvec(vec: list[float] | None):
    """将 float 列表转为 halfvec 文本字面量（None 返回 None，落库为 NULL）。

    采用字符串 + SQL ``::halfvec`` 强转，避免依赖 pgvector 的 asyncpg 类型注册，
    跨版本更稳健。
    """
    if not vec:
        return None
    return "[" + ",".join(repr(float(x)) for x in vec) + "]"


async def ensure_embeddings_table(pool) -> None:
    """确保 pgvector 扩展与 embeddings 表存在（应用启动时调用一次）。"""
    async with pool.acquire() as conn:
        await conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        await conn.execute(
            """
            CREATE TABLE IF NOT EXISTS embeddings (
                id BIGSERIAL PRIMARY KEY,
                tenant_id VARCHAR(64) NOT NULL,
                kb_id VARCHAR(64) NOT NULL,
                doc_id VARCHAR(64) NOT NULL,
                chunk_index INT NOT NULL DEFAULT 0,
                source VARCHAR(512),
                page INT,
                content TEXT NOT NULL,
                dimension INT NOT NULL,
                embedding halfvec,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
        await conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_embeddings_tenant ON embeddings(tenant_id)"
        )
        await conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_embeddings_doc ON embeddings(doc_id)"
        )
        # 扩展列：存储完整 metadata（含 chunk_type），供增强 worker 区分原始块 / qa 增强块
        await conn.execute(
            "ALTER TABLE embeddings ADD COLUMN IF NOT EXISTS metadata JSONB"
        )


def _build_store():
    """根据配置构建向量存储实例（单例）。"""
    if settings.vector_store_type == "milvus":
        return MilvusVectorStore()
    if settings.vector_store_type == "pgvector":
        return PgVectorStore()
    return InMemoryVectorStore()


vector_store_service = _build_store()
