"""PgVectorStore 单元测试 — 基于真实 Postgres（pgvector），隔离 test tenant。

验证：落库持久化、向量检索、租户/知识库过滤、删除、BM25 兜底检索，
以及「新实例 + warmup_bm25」模拟重启后兜底检索仍可用（对齐重启不丢知识）。

注意：asyncpg 连接池绑定事件循环，故整个测试类复用同一个事件循环，
避免 asyncio.run 反复建/销循环导致「Event loop is closed」。
"""

import asyncio
import unittest

from core.pg_client import close_pg_pool, get_pg_pool
from services.vector_store import PgVectorStore, ensure_embeddings_table

TEST_TENANT = "test_pgvector_tenant"
TEST_KB = "test_kb_pg"


class PgVectorStoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(cls.loop)
        cls.pool = cls.loop.run_until_complete(get_pg_pool())
        cls.loop.run_until_complete(ensure_embeddings_table(cls.pool))

    @classmethod
    def tearDownClass(cls):
        cls.loop.run_until_complete(close_pg_pool())
        cls.loop.close()

    def setUp(self):
        self.loop.run_until_complete(
            self.pool.execute("DELETE FROM embeddings WHERE tenant_id = $1", TEST_TENANT)
        )
        self.store = PgVectorStore()

    def tearDown(self):
        self.loop.run_until_complete(
            self.pool.execute("DELETE FROM embeddings WHERE tenant_id = $1", TEST_TENANT)
        )

    def _run(self, coro):
        return self.loop.run_until_complete(coro)

    def _chunks(self):
        return [
            {
                "content": "熊答是一款企业知识问答助手",
                "metadata": {"source": "a.txt", "page": 1, "chunk_index": 0},
                "embedding": [1.0, 0.0, 0.0],
            },
            {
                "content": "今天天气晴朗适合户外运动",
                "metadata": {"source": "b.txt", "page": 1, "chunk_index": 0},
                "embedding": [0.0, 1.0, 0.0],
            },
        ]

    def test_store_and_search(self):
        self._run(self.store.store_chunks(self._chunks(), TEST_KB, "doc1", TEST_TENANT))
        results = self._run(
            self.store.search([1.0, 0.0, 0.0], [TEST_KB], TEST_TENANT, top_k=5)
        )
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0].content, "熊答是一款企业知识问答助手")
        self.assertAlmostEqual(results[0].score, 1.0, places=4)

    def test_tenant_filter(self):
        self._run(self.store.store_chunks(self._chunks(), TEST_KB, "doc1", TEST_TENANT))
        results = self._run(
            self.store.search([1.0, 0.0, 0.0], [TEST_KB], "other_tenant")
        )
        self.assertEqual(results, [])

    def test_kb_filter(self):
        self._run(self.store.store_chunks(self._chunks(), TEST_KB, "doc1", TEST_TENANT))
        self.assertEqual(
            self._run(self.store.search([1.0, 0.0, 0.0], ["other_kb"], TEST_TENANT)), []
        )
        self.assertEqual(
            len(self._run(self.store.search([1.0, 0.0, 0.0], [TEST_KB], TEST_TENANT))), 2
        )

    def test_delete_by_doc(self):
        self._run(self.store.store_chunks(self._chunks(), TEST_KB, "doc1", TEST_TENANT))
        self._run(self.store.delete_by_doc("doc1"))
        self.assertEqual(
            self._run(self.store.search([1.0, 0.0, 0.0], [TEST_KB], TEST_TENANT)), []
        )

    def test_keyword_search_bm25(self):
        self._run(self.store.store_chunks(self._chunks(), TEST_KB, "doc1", TEST_TENANT))
        results = self._run(
            self.store.keyword_search("知识库 问答", [TEST_KB], TEST_TENANT)
        )
        self.assertTrue(len(results) >= 1)
        self.assertIn("知识", results[0].content)

    def test_warmup_after_restart(self):
        """模拟重启：新实例（空 BM25 内存）经 warmup_bm25 从 PG 恢复兜底检索。"""
        self._run(self.store.store_chunks(self._chunks(), TEST_KB, "doc1", TEST_TENANT))
        fresh = PgVectorStore()  # 模拟新进程，内存为空
        self._run(fresh.warmup_bm25())
        results = self._run(
            fresh.keyword_search("知识库 问答", [TEST_KB], TEST_TENANT)
        )
        self.assertTrue(len(results) >= 1)
        self.assertIn("知识", results[0].content)


if __name__ == "__main__":
    unittest.main()
