"""Elasticsearch 检索/搜索索引（M5-8）单元测试。

全部用 mock ES 客户端（AsyncMock），不依赖真实 ES 服务，也不依赖 Postgres；
``elasticsearch`` 包未安装时，`get_es_store()` 在 ``elasticsearch_enabled=False`` 下零导入、
完全不触发，旧链路零影响（见 ``test_get_es_store_disabled_no_import``）。

覆盖范围（对齐 M5-8 方案）：
- 索引惰性创建（每租户一索引，仅首次 create）
- 双写幂等：index_chunks 同 doc_id 先 delete_by_query 再 bulk，父块跳过
- BM25 查询构造：must_not 排除增强块 + outline（常规检索），大纲模式转 filter
- BM25 分数归一化（除以批次 max 再乘基准，量级对齐自研 BM25）
- delete_by_doc 跨索引按 doc_id 清理
- search_documents 文档级聚合（collapse + highlight，未来搜索预留）
- 存量迁移编排（reindex_from_pg / auto_reindex_missing）与真实 PG fetch 路径
- PgVectorStore 关键词路在 ES 启用时切换 + 异常降级自研 BM25
- _es_sync_chunks / _es_delete_doc 的启用/禁用/异常降级分支
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from core.config import settings
from services.elasticsearch_store import (
    ElasticsearchStore,
    _safe_tenant_index,
    close_es,
    get_es_store,
)
from services.vector_store import (
    AUGMENT_CHUNK_TYPES,
    PgVectorStore,
    RetrievalResult,
    _ChunkRecord,
    _es_delete_doc,
    _es_sync_chunks,
)


def _make_store() -> ElasticsearchStore:
    """绕过 __init__（避免 import elasticsearch 包）构造实例，client 用 AsyncMock。"""
    store = ElasticsearchStore.__new__(ElasticsearchStore)
    store._client = AsyncMock()
    store._index_cache = set()
    store._client.indices.exists = AsyncMock(return_value=False)
    store._client.indices.create = AsyncMock()
    store._client.delete_by_query = AsyncMock()
    store._client.bulk = AsyncMock()
    store._client.search = AsyncMock()
    return store


class ElasticsearchStoreCoreTest(unittest.TestCase):
    def setUp(self):
        self.store = _make_store()

    def test_safe_tenant_index(self):
        self.assertEqual(_safe_tenant_index("T1-2"), "xiongda_t12")
        self.assertEqual(_safe_tenant_index(""), "xiongda_default")

    def test_ensure_index_creates_once(self):
        idx = asyncio.run(self.store.ensure_index("t1"))
        self.assertEqual(idx, "xiongda_t1")
        self.store._client.indices.create.assert_awaited_once()
        asyncio.run(self.store.ensure_index("t1"))
        # 命中缓存，不再 create
        self.store._client.indices.create.assert_awaited_once()

    def test_index_chunks_skips_parent_and_builds_bulk(self):
        chunks = [
            {
                "content": "原文子块",
                "metadata": {"source": "a", "page": 1, "chunk_index": 0,
                             "chunk_type": "child", "doc_id": "d1", "kb_id": "k1", "tenant_id": "t1"},
            },
            {
                "content": "父块上下文",
                "metadata": {"source": "a", "page": 1, "chunk_index": 1, "chunk_type": "parent"},
            },
        ]
        asyncio.run(self.store.index_chunks(chunks, "k1", "d1", "t1"))
        # 幂等：先按 doc_id 清旧块
        self.store._client.delete_by_query.assert_awaited()
        args = self.store._client.delete_by_query.call_args.kwargs
        self.assertEqual(args["body"]["query"]["term"]["doc_id"], "d1")
        # bulk：父块跳过 → 仅 1 个 doc（action + source = 2 条）
        ops = self.store._client.bulk.call_args.kwargs["operations"]
        self.assertEqual(len(ops), 2)
        self.assertEqual(ops[0]["index"]["_index"], "xiongda_t1")
        self.assertEqual(ops[1]["content"], "原文子块")
        self.assertEqual(ops[0]["index"]["_id"], "d1|0|child")

    def test_index_chunks_idempotent_reinsert(self):
        chunks = [{"content": "x", "metadata": {"chunk_index": 0, "doc_id": "d1", "kb_id": "k1"}}]
        asyncio.run(self.store.index_chunks(chunks, "k1", "d1", "t1"))
        asyncio.run(self.store.index_chunks(chunks, "k1", "d1", "t1"))
        # 每次都先 delete 再 bulk → delete_by_query 被调 2 次（幂等不膨胀）
        self.assertEqual(self.store._client.delete_by_query.await_count, 2)
        self.assertEqual(self.store._client.bulk.await_count, 2)

    def test_keyword_search_normalizes_and_filters(self):
        self.store._client.search = AsyncMock(return_value={
            "hits": {"hits": [
                {"_score": 3.0, "_source": {"content": "A", "source": "a", "page": 1,
                                            "doc_id": "d1", "kb_id": "k1", "chunk_index": 0, "chunk_type": "child"}},
                {"_score": 1.0, "_source": {"content": "B", "source": "b", "page": 1,
                                            "doc_id": "d2", "kb_id": "k1", "chunk_index": 0, "chunk_type": "child"}},
                {"_score": 2.0, "_source": {"content": "QA增强块", "source": "", "page": 1,
                                            "doc_id": "d3", "kb_id": "k1", "chunk_index": 0, "chunk_type": "qa"}},
            ]}
        })
        res = asyncio.run(self.store.keyword_search("知识库", ["k1"], "t1", top_k=10))
        # 返回全部 3（服务端 must_not 由 ES 执行，mock 不实际过滤；此处验证查询构造与归一化）
        self.assertEqual(len(res), 3)
        # 最佳命中归一化到基准 5.0（max=3.0 → 3/3*5=5.0）
        self.assertAlmostEqual(res[0].score, 5.0, places=4)
        body = self.store._client.search.call_args.kwargs["body"]
        must_not = body["query"]["bool"]["must_not"]
        # 常规检索排除 outline
        self.assertTrue(any(m.get("term", {}).get("chunk_type") == "outline" for m in must_not))
        # 排除全部增强块类型
        self.assertTrue(any(
            set(m.get("terms", {}).get("chunk_type", [])) >= set(AUGMENT_CHUNK_TYPES)
            for m in must_not
        ))

    def test_keyword_search_outline_mode(self):
        self.store._client.search = AsyncMock(return_value={"hits": {"hits": []}})
        asyncio.run(self.store.keyword_search("架构", ["k1"], "t1", chunk_types=["outline"]))
        body = self.store._client.search.call_args.kwargs["body"]
        must_not = body["query"]["bool"]["must_not"]
        # 指定 chunk_types 时不排除 outline
        self.assertFalse(any("outline" in m.get("term", {}) for m in must_not))
        # 而是作为 filter
        filters = body["query"]["bool"]["filter"]
        self.assertTrue(any(m.get("terms", {}).get("chunk_type") == ["outline"] for m in filters))

    def test_keyword_search_kb_filter(self):
        self.store._client.search = AsyncMock(return_value={"hits": {"hits": []}})
        asyncio.run(self.store.keyword_search("q", ["kbX"], "t1"))
        body = self.store._client.search.call_args.kwargs["body"]
        filters = body["query"]["bool"]["filter"]
        self.assertTrue(any(m.get("terms", {}).get("kb_id") == ["kbX"] for m in filters))

    def test_delete_by_doc_cross_index(self):
        asyncio.run(self.store.delete_by_doc("d9"))
        self.store._client.delete_by_query.assert_awaited_once()
        args = self.store._client.delete_by_query.call_args.kwargs
        self.assertEqual(args["index"], "xiongda_*")
        self.assertEqual(args["body"]["query"]["term"]["doc_id"], "d9")

    def test_search_documents_collapse_and_highlight(self):
        self.store._client.search = AsyncMock(return_value={"hits": {"hits": [
            {"_source": {"doc_id": "d1", "kb_id": "k1", "source": "a.txt"},
             "highlight": {"content": ["<em>知识</em>"]}}
        ]}})
        res = asyncio.run(self.store.search_documents("知识", "t1"))
        self.assertEqual(res[0]["doc_id"], "d1")
        self.assertEqual(res[0]["highlight"], ["<em>知识</em>"])
        body = self.store._client.search.call_args.kwargs["body"]
        self.assertEqual(body["collapse"]["field"], "doc_id")

    def test_reindex_from_pg_orchestration(self):
        self.store._pg_tenants_with_data = AsyncMock(return_value=["t1", "t2"])
        calls = []
        async def fake_reindex(t, batch_size=200):
            calls.append(t)
            return 3
        self.store.reindex_tenant = fake_reindex
        total = asyncio.run(self.store.reindex_from_pg())
        self.assertEqual(calls, ["t1", "t2"])
        self.assertEqual(total, 6)

    def test_auto_reindex_missing_only_absent(self):
        self.store._pg_tenants_with_data = AsyncMock(return_value=["t1", "t2"])
        # t1 索引已存在（跳过），t2 不存在（迁移）
        self.store._client.indices.exists = AsyncMock(side_effect=lambda index: index == "xiongda_t1")
        calls = []
        async def fake_reindex(t, batch_size=200):
            calls.append(t)
            return 2
        self.store.reindex_tenant = fake_reindex
        n = asyncio.run(self.store.auto_reindex_missing())
        self.assertEqual(calls, ["t2"])
        self.assertEqual(n, 1)

    def test_reindex_tenant_reads_pg_and_bulk(self):
        pool = MagicMock()
        cm = AsyncMock()
        conn = AsyncMock()
        cm.__aenter__.return_value = conn
        cm.__aexit__.return_value = None
        pool.acquire = MagicMock(return_value=cm)
        conn.fetch.return_value = [
            {"content": "x", "source": "a", "page": 1, "doc_id": "d1", "kb_id": "k1",
             "chunk_index": 0, "chunk_type": None},
            {"content": "y", "source": "b", "page": 2, "doc_id": "d2", "kb_id": "k1",
             "chunk_index": 0, "chunk_type": "qa"},
        ]
        with patch("core.pg_client.get_pg_pool", AsyncMock(return_value=pool)):
            n = asyncio.run(self.store.reindex_tenant("t1"))
        self.assertEqual(n, 2)
        self.store._client.indices.create.assert_awaited()
        # 全量重建：先清空该租户索引
        self.store._client.delete_by_query.assert_awaited()
        ops = self.store._client.bulk.call_args.kwargs["operations"]
        # 2 个 doc → 4 条（action + source 各一）
        self.assertEqual(len(ops), 4)


class PgVectorStoreEsSwitchTest(unittest.TestCase):
    """PgVectorStore 在 ES 启用时关键词路切换 + 降级（不依赖真实 PG / ES）。"""

    def setUp(self):
        self.store = PgVectorStore()
        self.fake_es = _make_store()
        self.patcher = patch(
            "services.elasticsearch_store.get_es_store", return_value=self.fake_es
        )
        self.patcher.start()
        # 用 AsyncMock spy 替换，便于断言 ES 方法被调用 / 未调用
        self.fake_es.index_chunks = AsyncMock()
        self.fake_es.delete_by_doc = AsyncMock()
        self._orig_enabled = settings.elasticsearch_enabled
        settings.elasticsearch_enabled = True

    def tearDown(self):
        self.patcher.stop()
        settings.elasticsearch_enabled = self._orig_enabled

    def test_keyword_search_uses_es(self):
        self.fake_es.keyword_search = AsyncMock(
            return_value=[RetrievalResult(content="ES命中", source="", score=1.0)]
        )
        res = asyncio.run(self.store.keyword_search("q", ["k1"], "t1"))
        self.assertEqual(res[0].content, "ES命中")
        self.fake_es.keyword_search.assert_awaited()

    def test_keyword_search_fallback_on_es_error(self):
        self.fake_es.keyword_search = AsyncMock(side_effect=RuntimeError("boom"))
        # 注入自研 BM25 内存候选
        self.store._bm25_records = [
            _ChunkRecord(
                "熊答 企业 知识库 问答 助手",
                None,
                {"source": "a", "page": 1, "doc_id": "d1", "kb_id": "k1",
                 "chunk_index": 0, "tenant_id": "t1", "chunk_type": "child"},
            )
        ]
        res = asyncio.run(self.store.keyword_search("知识库 问答", ["k1"], "t1"))
        self.assertTrue(len(res) >= 1)
        self.assertIn("知识库", res[0].content)

    def test_es_sync_chunks_called_when_enabled(self):
        asyncio.run(_es_sync_chunks([{"content": "x", "metadata": {}}], "k1", "d1", "t1"))
        self.fake_es.index_chunks.assert_awaited()

    def test_es_sync_chunks_skipped_when_disabled(self):
        settings.elasticsearch_enabled = False
        asyncio.run(_es_sync_chunks([{"content": "x", "metadata": {}}], "k1", "d1", "t1"))
        self.fake_es.index_chunks.assert_not_awaited()

    def test_es_sync_chunks_no_raise_on_error(self):
        self.fake_es.index_chunks = AsyncMock(side_effect=RuntimeError("boom"))
        # 不应向上抛
        asyncio.run(_es_sync_chunks([{"content": "x", "metadata": {}}], "k1", "d1", "t1"))

    def test_es_delete_doc_called_when_enabled(self):
        asyncio.run(_es_delete_doc("d1"))
        self.fake_es.delete_by_doc.assert_awaited()

    def test_es_delete_doc_skipped_when_disabled(self):
        settings.elasticsearch_enabled = False
        asyncio.run(_es_delete_doc("d1"))
        self.fake_es.delete_by_doc.assert_not_awaited()

    def test_es_delete_doc_no_raise_on_error(self):
        self.fake_es.delete_by_doc = AsyncMock(side_effect=RuntimeError("boom"))
        asyncio.run(_es_delete_doc("d1"))


class EsStoreLifecycleTest(unittest.TestCase):
    def setUp(self):
        self._orig_enabled = settings.elasticsearch_enabled
        settings.elasticsearch_enabled = False

    def tearDown(self):
        settings.elasticsearch_enabled = self._orig_enabled

    def test_get_es_store_disabled_no_import(self):
        # 关闭时直接返回 None，不触发 elasticsearch 包导入（零依赖降级验证）
        self.assertIsNone(get_es_store())

    def test_close_es_no_store(self):
        # _es_store 为 None 时关闭不抛
        asyncio.run(close_es())


if __name__ == "__main__":
    unittest.main()
