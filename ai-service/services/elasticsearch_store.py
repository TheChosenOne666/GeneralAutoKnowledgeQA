"""Elasticsearch 检索引擎（M5-8）：为 RAG 提供工业级 BM25 关键词召回，并为后续「搜索功能」

提供统一索引。

设计要点（对齐腾讯 WeKnora：ES 一个引擎同时做 kNN + BM25，RAG 检索只做召回 + RRF，
Rerank 由现有 ``rag.py`` 负责；PG 保留为分块权威存储）：
- PG 仍是分块真相源（预览 / 删除 / 失效 / 父子块 / 重建全部不动），本模块只做「可搜索索引 + 未来搜索源」。
- 方案 A（零维度风险）：向量召回仍走 pgvector；ES **只负责 BM25 关键词召回 + 未来搜索**，
  ``dense_vector`` 本期仅随块存储（``index: false``）备用，不用于召回。
- 每租户一个索引 ``{prefix}_{tenant_id}``（惰性创建）：固定 ``dense_vector`` 维度由
  ``embedding_dimension`` 配置驱动，天然租户隔离，与现有 L1 缓存按 tenant 一致。
- 双写幂等：``store_chunks`` 落 PG 后调 :meth:`index_chunks`，同 ``doc_id`` 先
  ``delete_by_query`` 再 bulk 插入，杜绝重复 / 膨胀。
- 优雅降级：所有 ES 调用失败均被调用方捕获并记录告警，不中断 PG 主路径与问答。

本模块把所有 ``elasticsearch`` 客户端 import 收敛在构造与调用处，``elasticsearch_enabled=False``
时不会被加载，未安装依赖也不影响旧链路（详见 :func:`get_es_store`）。
"""

import re
from typing import Any

from loguru import logger

from core.config import settings
from services.vector_store import AUGMENT_CHUNK_TYPES, RetrievalResult, _exclude_chunk_types_sql


def _safe_tenant_index(tenant_id: str) -> str:
    """将租户 ID 转为合法的 ES 索引名后缀（小写字母数字，空白字符移除）。"""
    safe = re.sub(r"[^a-z0-9]", "", (tenant_id or "").lower())
    return f"{settings.elasticsearch_index_prefix}_{safe or 'default'}"


def _chunk_mapping() -> dict:
    """返回 ES 索引 mappings（``indices.create`` 的 ``mappings`` 关键字参数）。"""
    return {
        "mappings": {
            "properties": {
                # content 用可配置 analyzer（默认 standard；装 IK 插件后可设 ik_max_word 提升中文 BM25）
                "content": {"type": "text", "analyzer": settings.elasticsearch_text_analyzer},
                # 本期不用于 kNN 召回（仅随块存储备用）。注意：ES 7.x 不支持 dense_vector 的
                # index 参数（8.x 才引入 HNSW index 开关），此处不写 index 以同时兼容 7.x / 8.x 服务器，
                # dims 由配置固定避免变维度报错。
                "embedding": {
                    "type": "dense_vector",
                    "dims": settings.embedding_dimension,
                },
                "doc_id": {"type": "keyword"},
                "kb_id": {"type": "keyword"},
                "tenant_id": {"type": "keyword"},
                "chunk_index": {"type": "integer"},
                "chunk_type": {"type": "keyword"},
                "page": {"type": "integer"},
                "source": {"type": "keyword"},
                "metadata": {"type": "flattened"},
            }
        }
    }


class ElasticsearchStore:
    """Elasticsearch 检索 / 搜索索引封装（M5-8）。"""

    def __init__(self) -> None:
        from elasticsearch import AsyncElasticsearch

        hosts = [h.strip() for h in settings.elasticsearch_hosts.split(",") if h.strip()]
        if not hosts:
            raise RuntimeError("Elasticsearch 启用但 elasticsearch_hosts 为空")
        auth: dict[str, Any] = {}
        if settings.elasticsearch_api_key:
            auth["api_key"] = settings.elasticsearch_api_key
        elif settings.elasticsearch_user:
            auth["basic_auth"] = (settings.elasticsearch_user, settings.elasticsearch_password)
        # 兼容模式：8.x 客户端连接 7.x 服务器时下发 compatible-with=7 的 accept header，
        # 使服务端返回 7.x 兼容响应格式（避免 UnsupportedProductError）。连 8.x 服务器时此值为空。
        headers: dict[str, str] = {}
        if settings.elasticsearch_compat_7:
            headers["accept"] = "application/vnd.elasticsearch+json;compatible-with=7"
        self._client = AsyncElasticsearch(
            hosts=hosts,
            request_timeout=settings.elasticsearch_request_timeout,
            headers=headers or None,
            **auth,
        )
        self._index_cache: set[str] = set()
        logger.info(f"[ES] 已连接 Elasticsearch hosts={hosts}")

    async def close(self) -> None:
        """关闭 ES 客户端连接。"""
        try:
            await self._client.close()
        except Exception as e:  # noqa: BLE001 - 关闭失败不应中断关停
            logger.warning(f"[ES] 关闭客户端失败: {e}")

    async def ensure_index(self, tenant_id: str) -> str:
        """确保租户索引存在（惰性创建），返回索引名。"""
        index = _safe_tenant_index(tenant_id)
        if index in self._index_cache:
            return index
        exists = await self._client.indices.exists(index=index)
        if not exists:
            await self._client.indices.create(index=index, **_chunk_mapping())
            logger.info(f"[ES] 已创建索引 index={index} dims={settings.embedding_dimension}")
        self._index_cache.add(index)
        return index

    def _to_es_doc(self, chunk: dict, kb_id: str, doc_id: str, tenant_id: str) -> dict | None:
        """将分块字典转为 ES 文档；parent 父块跳过（仅供回溯，不进关键词检索）。"""
        meta = dict(chunk.get("metadata") or {})
        if meta.get("chunk_type") == "parent":
            return None
        emb = chunk.get("embedding")
        doc = {
            "content": chunk["content"],
            "doc_id": meta.get("doc_id", doc_id),
            "kb_id": meta.get("kb_id", kb_id),
            "tenant_id": meta.get("tenant_id", tenant_id),
            "chunk_index": int(meta.get("chunk_index", 0)),
            "chunk_type": meta.get("chunk_type") or "",
            "page": int(meta.get("page", 0) or 0),
            "source": meta.get("source", ""),
            "metadata": meta,
        }
        # 仅当维度与索引一致才写入 embedding，避免变维度报错（BM25 不依赖 embedding）
        if emb and len(emb) == settings.embedding_dimension:
            doc["embedding"] = emb
        return doc

    @staticmethod
    def _doc_id(doc: dict) -> str:
        """ES 文档 _id：租户内按 doc_id+chunk_index+chunk_type 唯一，保证幂等覆盖。"""
        return f"{doc['doc_id']}|{doc['chunk_index']}|{doc['chunk_type']}"

    async def index_chunks(
        self, chunks: list[dict], kb_id: str, doc_id: str, tenant_id: str
    ) -> None:
        """双写 ES：同 ``doc_id`` 先 delete_by_query 再 bulk 插入（幂等，不膨胀）。"""
        docs = [d for d in (self._to_es_doc(c, kb_id, doc_id, tenant_id) for c in chunks) if d]
        if not docs:
            return
        index = await self.ensure_index(tenant_id)
        # 幂等：清同 doc_id 旧块（文档重处理不产生重复）
        await self._client.delete_by_query(
            index=index,
            body={"query": {"term": {"doc_id": doc_id}}},
            conflicts="proceed",
        )
        operations: list[dict] = []
        for d in docs:
            operations.append({"index": {"_index": index, "_id": self._doc_id(d)}})
            operations.append(d)
        await self._client.bulk(operations=operations, refresh="wait_for")
        logger.info(f"[ES] 双写分块 doc_id={doc_id} 块数={len(docs)} index={index}")

    async def keyword_search(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_k: int = 20,
        chunk_types: list[str] | None = None,
    ) -> list[RetrievalResult]:
        """ES BM25 关键词检索 Top-K。

        Args:
            chunk_types: 仅返回该列表内的 ``chunk_type``；为 None 时常规检索排除
                outline（大纲意图显式传 ``["outline"]``）。
        """
        index = await self.ensure_index(tenant_id)
        must_not = [{"terms": {"chunk_type": list(AUGMENT_CHUNK_TYPES)}}]
        filters: list[dict] = []
        if chunk_types is None:
            must_not.append({"term": {"chunk_type": "outline"}})
        else:
            filters.append({"terms": {"chunk_type": chunk_types}})
        if kb_ids:
            filters.append({"terms": {"kb_id": list(kb_ids)}})
        body = {
            "size": top_k,
            "query": {
                "bool": {
                    "must": [{"match": {"content": query}}],
                    "filter": filters,
                    "must_not": must_not,
                }
            },
            "highlight": {"fields": {"content": {}}},
        }
        resp = await self._client.search(index=index, body=body)
        hits = resp["hits"]["hits"]
        if not hits:
            return []
        max_score = max(h["_score"] for h in hits) or 1.0
        results: list[RetrievalResult] = []
        for h in hits:
            src = h["_source"]
            # 归一化：除以批次内 max 再乘基准，使量级接近自研 BM25，融合补充门槛行为一致
            norm = (h["_score"] / max_score) * settings.elasticsearch_bm25_scale
            # M6-3：从 metadata 中解析 negative_questions
            meta = src.get("metadata") or {}
            nqs = meta.get("negative_questions", [])
            if not isinstance(nqs, list):
                nqs = []
            results.append(
                RetrievalResult(
                    content=src.get("content", ""),
                    source=src.get("source", ""),
                    page=src.get("page", 0) or 0,
                    score=float(norm),
                    doc_id=src.get("doc_id", ""),
                    kb_id=src.get("kb_id", ""),
                    chunk_index=src.get("chunk_index", 0),
                    chunk_type=src.get("chunk_type", ""),
                    negative_questions=nqs,
                )
            )
        return results

    async def delete_by_doc(self, doc_id: str) -> None:
        """按 ``doc_id`` 跨所有租户索引删除 ES 分块（调用方不一定有 tenant_id）。"""
        index_pattern = f"{settings.elasticsearch_index_prefix}_*"
        await self._client.delete_by_query(
            index=index_pattern,
            body={"query": {"term": {"doc_id": doc_id}}},
            conflicts="proceed",
            refresh=True,
        )
        logger.info(f"[ES] 删除分块 doc_id={doc_id} pattern={index_pattern}")

    async def search_documents_enhanced(
        self,
        query: str,
        tenant_id: str,
        kb_ids: list[str] | None = None,
        top_k: int = 10,
        from_: int = 0,
        embedding: list[float] | None = None,
    ) -> dict:
        """增强版文档搜索：BM25 多字段 + 向量 kNN 召回 + RRF 融合 + 高亮。

        搜索字段：``content`` (BM25) + ``source`` (文件名 BM25)。
        若提供 ``embedding``，同时执行 kNN 向量召回，用 RRF 融合两路结果。

        返回 ``{"total": int, "hits": [{"doc_id", "kb_id", "source", "content",
        "highlight", "score", "doc_count"}]}``。
        """
        index = await self.ensure_index(tenant_id)
        must_not = [{"terms": {"chunk_type": list(AUGMENT_CHUNK_TYPES + ("parent",))}}]
        filters: list[dict] = []
        if kb_ids:
            filters.append({"terms": {"kb_id": list(kb_ids)}})

        # --- BM25 多字段查询 ---
        bm25_query: dict[str, Any] = {
            "bool": {
                "must": [
                    {
                        "multi_match": {
                            "query": query,
                            "fields": ["content^3", "source^2"],
                            "type": "best_fields",
                            "operator": "or",
                        }
                    }
                ],
                "filter": filters,
                "must_not": must_not,
            }
        }

        # --- 向量 kNN 召回（可选） ---
        use_knn = embedding is not None and len(embedding) == settings.embedding_dimension
        if use_knn:
            # ES 7.x 用 script_score 模拟 kNN（7.x 无原生 kNN search API）
            knn_query: dict[str, Any] = {
                "bool": {
                    "must": [
                        {
                            "script_score": {
                                "query": {"match_all": {}},
                                "script": {
                                    "source": "cosineSimilarity(params.query_vector, 'embedding') + 1.0",
                                    "params": {"query_vector": embedding},
                                },
                            }
                        }
                    ],
                    "filter": filters,
                    "must_not": must_not,
                }
            }

            # 分别查 BM25 和 kNN，然后 RRF 融合
            bm25_size = min(top_k * 3, 50)
            knn_size = min(top_k * 3, 50)

            bm25_body = {
                "size": bm25_size,
                "query": bm25_query,
                "highlight": {"fields": {"content": {"fragment_size": 150, "number_of_fragments": 2}}, "source": {"fragment_size": 80}},
            }
            knn_body = {
                "size": knn_size,
                "query": knn_query,
            }

            import asyncio
            bm25_resp, knn_resp = await asyncio.gather(
                self._client.search(index=index, body=bm25_body),
                self._client.search(index=index, body=knn_body),
            )

            # RRF 融合
            rrf_k = 60
            rrf_scores: dict[str, float] = {}
            src_map: dict[str, dict] = {}
            highlight_map: dict[str, list] = {}

            for rank, h in enumerate(bm25_resp["hits"]["hits"]):
                eid = h["_id"]
                rrf_scores[eid] = rrf_scores.get(eid, 0) + 1.0 / (rrf_k + rank + 1)
                src_map[eid] = h["_source"]
                hl = h.get("highlight", {})
                highlight_map[eid] = hl.get("content", [])

            for rank, h in enumerate(knn_resp["hits"]["hits"]):
                eid = h["_id"]
                rrf_scores[eid] = rrf_scores.get(eid, 0) + 1.0 / (rrf_k + rank + 1)
                if eid not in src_map:
                    src_map[eid] = h["_source"]

            # 按 RRF 分数排序
            ranked = sorted(rrf_scores.items(), key=lambda x: x[1], reverse=True)
            total = len(ranked)
            # 分页
            ranked = ranked[from_ : from_ + top_k]

            out: list[dict] = []
            for eid, score in ranked:
                src = src_map.get(eid, {})
                hl_list = highlight_map.get(eid, [])
                hl = hl_list[0] if hl_list else ""
                out.append({
                    "doc_id": src.get("doc_id", ""),
                    "kb_id": src.get("kb_id", ""),
                    "source": src.get("source", ""),
                    "content": (src.get("content", ""))[:200],
                    "highlight": hl,
                    "score": round(score, 6),
                })
            return {"total": total, "hits": out}

        else:
            # 纯 BM25（无向量）
            body = {
                "size": top_k,
                "from": from_,
                "query": bm25_query,
                "collapse": {"field": "doc_id"},
                "highlight": {"fields": {"content": {"fragment_size": 150, "number_of_fragments": 2}, "source": {"fragment_size": 80}}},
            }
            resp = await self._client.search(index=index, body=body)
            total = resp["hits"]["total"]["value"] if isinstance(resp["hits"]["total"], dict) else resp["hits"]["total"]
            out: list[dict] = []
            for h in resp["hits"]["hits"]:
                src = h["_source"]
                hl_list = h.get("highlight", {}).get("content", [])
                hl = hl_list[0] if hl_list else ""
                out.append({
                    "doc_id": src.get("doc_id", ""),
                    "kb_id": src.get("kb_id", ""),
                    "source": src.get("source", ""),
                    "content": (src.get("content", ""))[:200],
                    "highlight": hl,
                    "score": float(h["_score"] or 0),
                })
            return {"total": total, "hits": out}

    async def _pg_tenants_with_data(self) -> list[str]:
        """读 PG 中实际有分块（非增强 / 非父块）的租户列表（存量迁移用）。"""
        from core.pg_client import get_pg_pool

        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                f"""SELECT DISTINCT tenant_id
                   FROM embeddings
                   WHERE metadata->>'chunk_type' IS DISTINCT FROM 'parent'
                     {_exclude_chunk_types_sql(AUGMENT_CHUNK_TYPES)}"""
            )
        return [r["tenant_id"] for r in rows]

    async def reindex_tenant(self, tenant_id: str, batch_size: int = 200) -> int:
        """从 PG 重建单个租户的 ES 索引（全量覆盖，幂等）。仅写文本 + 元数据（BM25 足够）。"""
        from core.pg_client import get_pg_pool

        pool = await get_pg_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                f"""SELECT content, source, page, doc_id, kb_id, chunk_index,
                           metadata->>'chunk_type' AS chunk_type
                    FROM embeddings
                    WHERE tenant_id = $1
                      AND metadata->>'chunk_type' IS DISTINCT FROM 'parent'
                      {_exclude_chunk_types_sql(AUGMENT_CHUNK_TYPES)}
                    ORDER BY doc_id, chunk_index""",
                tenant_id,
            )
        if not rows:
            return 0
        index = await self.ensure_index(tenant_id)
        # 全量重建：先清该租户索引再写
        await self._client.delete_by_query(
            index=index, body={"query": {"match_all": {}}}, conflicts="proceed"
        )
        operations: list[dict] = []
        for r in rows:
            doc = {
                "content": r["content"],
                "doc_id": r["doc_id"],
                "kb_id": r["kb_id"],
                "tenant_id": tenant_id,
                "chunk_index": r["chunk_index"] or 0,
                "chunk_type": r["chunk_type"] or "",
                "page": r["page"] or 0,
                "source": r["source"] or "",
            }
            operations.append({"index": {"_index": index, "_id": self._doc_id(doc)}})
            operations.append(doc)
        for i in range(0, len(operations), batch_size * 2):
            await self._client.bulk(operations=operations[i : i + batch_size * 2], refresh="wait_for")
        logger.info(f"[ES] 存量迁移 tenant={tenant_id} 块数={len(rows)}")
        return len(rows)

    async def reindex_from_pg(self, batch_size: int = 200) -> int:
        """存量迁移：从 PG 全量重建所有租户的 ES 索引，返回迁移块总数。"""
        tenants = await self._pg_tenants_with_data()
        total = 0
        for t in tenants:
            total += await self.reindex_tenant(t, batch_size=batch_size)
        logger.info(f"[ES] 存量迁移完成，租户数={len(tenants)} 块数={total}")
        return total

    async def auto_reindex_missing(self, batch_size: int = 200) -> int:
        """启动补迁：仅为「PG 有数据但 ES 索引尚不存在」的租户建索引并迁移（不覆盖已有索引）。"""
        tenants = await self._pg_tenants_with_data()
        migrated = 0
        for t in tenants:
            index = _safe_tenant_index(t)
            if await self._client.indices.exists(index=index):
                continue
            await self.reindex_tenant(t, batch_size=batch_size)
            migrated += 1
        if migrated:
            logger.info(f"[ES] 启动补迁完成，租户数={migrated}")
        return migrated

    # ==================== 聊天消息搜索（全局搜索） ====================

    def _msg_index(self, tenant_id: str) -> str:
        """消息索引名：``{prefix}_msg_{tenant_id}``，与 chunk 索引隔离。"""
        safe = re.sub(r"[^a-z0-9]", "", (tenant_id or "").lower())
        return f"{settings.elasticsearch_index_prefix}_msg_{safe or 'default'}"

    def _msg_mapping(self) -> dict:
        """消息索引 mappings。"""
        return {
            "mappings": {
                "properties": {
                    "content": {"type": "text", "analyzer": settings.elasticsearch_text_analyzer},
                    "conversation_id": {"type": "keyword"},
                    "conversation_title": {"type": "text", "analyzer": "standard"},
                    "role": {"type": "keyword"},
                    "tenant_id": {"type": "keyword"},
                    "user_id": {"type": "keyword"},
                    "create_time": {"type": "date"},
                }
            }
        }

    async def ensure_msg_index(self, tenant_id: str) -> str:
        """确保租户消息索引存在（惰性创建），返回索引名。"""
        index = self._msg_index(tenant_id)
        if index in self._index_cache:
            return index
        exists = await self._client.indices.exists(index=index)
        if not exists:
            await self._client.indices.create(index=index, **self._msg_mapping())
            logger.info(f"[ES] 已创建消息索引 index={index}")
        self._index_cache.add(index)
        return index

    async def index_message(
        self,
        message_id: str,
        conversation_id: str,
        conversation_title: str,
        role: str,
        content: str,
        tenant_id: str,
        user_id: str,
        create_time: str,
    ) -> None:
        """索引单条聊天消息到 ES（幂等：同 message_id 先删再插）。"""
        index = await self.ensure_msg_index(tenant_id)
        doc = {
            "content": content,
            "conversation_id": conversation_id,
            "conversation_title": conversation_title,
            "role": role,
            "tenant_id": tenant_id,
            "user_id": user_id,
            "create_time": create_time,
        }
        await self._client.index(index=index, id=message_id, document=doc, refresh="wait_for")
        logger.debug(f"[ES] 索引消息 msg_id={message_id} conv={conversation_id} index={index}")

    async def search_messages(
        self,
        query: str,
        tenant_id: str,
        user_id: str | None = None,
        top_k: int = 10,
        from_: int = 0,
    ) -> dict:
        """BM25 多字段搜索聊天消息（content + conversation_title），返回分页结果。

        返回 ``{"total": int, "hits": [{id, conversation_id, conversation_title, role, content, highlight, score}]}``。
        """
        index = await self.ensure_msg_index(tenant_id)
        filters: list[dict] = []
        if user_id:
            filters.append({"term": {"user_id": user_id}})
        body = {
            "size": top_k,
            "from": from_,
            "query": {
                "bool": {
                    "must": [
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["content^3", "conversation_title^2"],
                                "type": "best_fields",
                                "operator": "or",
                            }
                        }
                    ],
                    "filter": filters,
                }
            },
            "highlight": {
                "fields": {
                    "content": {"fragment_size": 150, "number_of_fragments": 1},
                    "conversation_title": {"fragment_size": 80},
                },
            },
        }
        resp = await self._client.search(index=index, body=body)
        hits = resp["hits"]["hits"]
        total = resp["hits"]["total"]["value"] if isinstance(resp["hits"]["total"], dict) else resp["hits"]["total"]
        if not hits:
            return {"total": total, "hits": []}
        out: list[dict] = []
        for h in hits:
            src = h["_source"]
            highlight_list = h.get("highlight", {}).get("content", [])
            highlight = highlight_list[0] if highlight_list else ""
            content = src.get("content", "")
            out.append(
                {
                    "id": h["_id"],
                    "conversation_id": src.get("conversation_id", ""),
                    "conversation_title": src.get("conversation_title", ""),
                    "role": src.get("role", ""),
                    "content": content[:200],
                    "highlight": highlight,
                    "score": float(h["_score"] or 0),
                }
            )
        return {"total": total, "hits": out}

    async def delete_messages_by_conversation(self, conversation_id: str, tenant_id: str) -> None:
        """删除某会话的所有消息索引（会话删除时调用）。"""
        index = self._msg_index(tenant_id)
        await self._client.delete_by_query(
            index=index,
            body={"query": {"term": {"conversation_id": conversation_id}}},
            conflicts="proceed",
            refresh=True,
        )
        logger.info(f"[ES] 删除会话消息索引 conv_id={conversation_id} index={index}")


_es_store: "ElasticsearchStore | None" = None


def get_es_store() -> "ElasticsearchStore | None":
    """获取 ES 检索单例（懒加载）。

    ``elasticsearch_enabled=False`` 时返回 ``None``，且不触发 ``elasticsearch`` 包导入，
    未安装依赖也完全不影响旧链路。
    """
    global _es_store
    if not settings.elasticsearch_enabled:
        return None
    if _es_store is None:
        _es_store = ElasticsearchStore()
    return _es_store


async def close_es() -> None:
    """关闭 ES 单例（应用关停时调用）。"""
    global _es_store
    if _es_store is not None:
        await _es_store.close()
        _es_store = None
