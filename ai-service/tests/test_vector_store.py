"""内存向量存储单元测试：检索/过滤/删除/BM25。"""

import asyncio
import unittest

from services.vector_store import InMemoryVectorStore


class InMemoryVectorStoreTest(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryVectorStore()

    def _run(self, coro):
        return asyncio.run(coro)

    def test_store_and_search(self):
        chunks = [
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
        self._run(self.store.store_chunks(chunks, "kb1", "doc1", "t1"))
        results = self._run(self.store.search([1.0, 0.0, 0.0], ["kb1"], "t1", top_k=5))
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0].content, "熊答是一款企业知识问答助手")
        self.assertAlmostEqual(results[0].score, 1.0, places=5)

    def test_tenant_filter(self):
        chunks = [
            {
                "content": "x",
                "metadata": {"source": "a.txt", "chunk_index": 0},
                "embedding": [1.0, 0.0],
            }
        ]
        self._run(self.store.store_chunks(chunks, "kb1", "doc1", "t1"))
        results = self._run(self.store.search([1.0, 0.0], ["kb1"], "t2"))
        self.assertEqual(results, [])

    def test_kb_filter(self):
        chunks = [
            {
                "content": "x",
                "metadata": {"source": "a.txt", "chunk_index": 0},
                "embedding": [1.0, 0.0],
            }
        ]
        self._run(self.store.store_chunks(chunks, "kb1", "doc1", "t1"))
        self.assertEqual(self._run(self.store.search([1.0, 0.0], ["kb2"], "t1")), [])
        self.assertEqual(len(self._run(self.store.search([1.0, 0.0], ["kb1"], "t1"))), 1)

    def test_delete_by_doc(self):
        chunks = [
            {
                "content": "x",
                "metadata": {"source": "a.txt", "chunk_index": 0},
                "embedding": [1.0, 0.0],
            }
        ]
        self._run(self.store.store_chunks(chunks, "kb1", "doc1", "t1"))
        self._run(self.store.delete_by_doc("doc1"))
        self.assertEqual(self._run(self.store.search([1.0, 0.0], ["kb1"], "t1")), [])

    def test_keyword_search_bm25(self):
        chunks = [
            {
                "content": "熊答 企业 知识库 问答 助手",
                "metadata": {"source": "a.txt", "chunk_index": 0},
                "embedding": [0.0, 1.0],
            },
            {
                "content": "足球 世界杯 比赛 进球",
                "metadata": {"source": "b.txt", "chunk_index": 0},
                "embedding": [1.0, 0.0],
            },
        ]
        self._run(self.store.store_chunks(chunks, "kb1", "doc1", "t1"))
        results = self._run(self.store.keyword_search("知识库 问答", ["kb1"], "t1"))
        self.assertTrue(len(results) >= 1)
        self.assertIn("知识库", results[0].content)


if __name__ == "__main__":
    unittest.main()
