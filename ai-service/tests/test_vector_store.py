"""内存向量存储单元测试：检索/过滤/删除/BM25。"""

import asyncio
import unittest

from services.vector_store import AUGMENT_CHUNK_TYPES, InMemoryVectorStore


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

    def test_parent_chunk_excluded_from_search(self):
        """M5-5：父块不进向量检索，子块可检索且带 parent_id。"""
        child = {
            "content": "熊答是企业知识问答助手的核心功能段落",
            "metadata": {
                "source": "a.txt", "page": 1, "chunk_index": 0,
                "chunk_type": "child", "parent_id": "p0",
            },
            "embedding": [1.0, 0.0],
        }
        parent = {
            "content": "熊答是企业知识问答助手，支持 RAG 检索与智能问答，具备多租户与成员管理能力。",
            "metadata": {
                "source": "a.txt", "page": 1, "chunk_index": 1,
                "chunk_type": "parent", "is_parent": True, "parent_id": "p0",
            },
            "embedding": None,  # 父块不向量化
        }
        self._run(self.store.store_chunks([child, parent], "kb1", "doc1", "t1"))
        results = self._run(self.store.search([1.0, 0.0], ["kb1"], "t1", top_k=5))
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].content, child["content"])
        self.assertEqual(results[0].parent_id, "p0")

    def test_keyword_search_excludes_parent(self):
        """M5-5：父块不进 BM25 关键词检索。"""
        child = {
            "content": "熊答 企业 知识库 问答 助手",
            "metadata": {
                "source": "a.txt", "chunk_index": 0,
                "chunk_type": "child", "parent_id": "p0",
            },
            "embedding": [0.0, 1.0],
        }
        parent = {
            "content": "熊答 企业 知识库 问答 助手 多租户 成员管理 权限",
            "metadata": {
                "source": "a.txt", "chunk_index": 1,
                "chunk_type": "parent", "is_parent": True, "parent_id": "p0",
            },
            "embedding": [0.0, 1.0],
        }
        self._run(self.store.store_chunks([child, parent], "kb1", "doc1", "t1"))
        results = self._run(self.store.keyword_search("知识库 问答", ["kb1"], "t1"))
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].content, child["content"])

    def test_attach_parents_fills_content(self):
        """M5-5：命中子块经 attach_parents 回溯得到父块完整内容。"""
        child = {
            "content": "熊答是企业知识问答助手的核心功能段落",
            "metadata": {
                "source": "a.txt", "page": 1, "chunk_index": 0,
                "chunk_type": "child", "parent_id": "p0",
            },
            "embedding": [1.0, 0.0],
        }
        parent = {
            "content": "熊答是企业知识问答助手，支持 RAG 检索与智能问答，具备多租户与成员管理能力。",
            "metadata": {
                "source": "a.txt", "page": 1, "chunk_index": 1,
                "chunk_type": "parent", "is_parent": True, "parent_id": "p0",
            },
            "embedding": None,
        }
        self._run(self.store.store_chunks([child, parent], "kb1", "doc1", "t1"))
        results = self._run(self.store.search([1.0, 0.0], ["kb1"], "t1", top_k=5))
        results = self._run(self.store.attach_parents(results))
        self.assertEqual(results[0].parent_content, parent["content"])

    def test_get_parents_and_excludes_parent(self):
        """M5-5：get_parents_by_doc 仅读父块；get_original_chunks/get_document_pages 排除父块。"""
        child = {
            "content": "子块内容",
            "metadata": {
                "source": "a.txt", "page": 1, "chunk_index": 0,
                "chunk_type": "child", "parent_id": "p0",
            },
            "embedding": [1.0, 0.0],
        }
        parent = {
            "content": "父块完整上下文内容",
            "metadata": {
                "source": "a.txt", "page": 1, "chunk_index": 1,
                "chunk_type": "parent", "is_parent": True, "parent_id": "p0",
            },
            "embedding": None,
        }
        self._run(self.store.store_chunks([child, parent], "kb1", "doc1", "t1"))
        parents = self._run(self.store.get_parents_by_doc("doc1"))
        self.assertEqual(len(parents), 1)
        self.assertEqual(parents[0]["content"], parent["content"])

        originals = self._run(self.store.get_original_chunks("doc1"))
        self.assertEqual(len(originals), 1)
        self.assertEqual(originals[0]["content"], child["content"])

        pages = self._run(self.store.get_document_pages("doc1"))
        joined = "".join(p["text"] for p in pages)
        self.assertNotIn("父块完整上下文内容", joined)
        self.assertIn("子块内容", joined)

    def test_get_original_chunks_excludes_all_augment_types(self):
        """M5-7+M5-6：get_original_chunks 排除全部增强块(含新增 ocr/image_caption)与 parent。"""
        blocks = [
            {"content": "原文块1", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 0, "chunk_type": "child"}},
            {"content": "Q: x A: y", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 1, "chunk_type": "qa"}},
            {"content": "推测问题?", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 2, "chunk_type": "question"}},
            {"content": "文档摘要", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 3, "chunk_type": "summary"}},
            {"content": "wiki 条目", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 4, "chunk_type": "wiki"}},
            {"content": "X 属于 Y", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 5, "chunk_type": "entity"}},
            {"content": "图中文字OCR", "metadata": {"source": "a.txt", "page": 2, "chunk_index": 200000, "chunk_type": "ocr"}},
            {"content": "图描述caption", "metadata": {"source": "a.txt", "page": 2, "chunk_index": 300000, "chunk_type": "image_caption"}},
            {"content": "父块", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 6, "chunk_type": "parent", "is_parent": True}},
        ]
        self._run(self.store.store_chunks(
            [{"content": b["content"], "metadata": b["metadata"], "embedding": None} for b in blocks],
            "kb1", "doc1", "t1",
        ))
        res = self._run(self.store.get_original_chunks("doc1"))
        self.assertEqual(len(res), 1)
        self.assertEqual(res[0]["content"], "原文块1")
        # 新增多模态块类型已在 AUGMENT_CHUNK_TYPES 中（受同一排除逻辑覆盖）
        self.assertIn("ocr", AUGMENT_CHUNK_TYPES)
        self.assertIn("image_caption", AUGMENT_CHUNK_TYPES)

    def test_get_document_pages_excludes_augment_and_parent(self):
        """M5-7+M5-6：get_document_pages 排除增强块(含 ocr/image_caption)与 parent（避免预览噪声）。"""
        blocks = [
            {"content": "原文块1", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 0, "chunk_type": "child"}},
            {"content": "Q: x A: y", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 1, "chunk_type": "qa"}},
            {"content": "推测问题?", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 2, "chunk_type": "question"}},
            {"content": "文档摘要", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 3, "chunk_type": "summary"}},
            {"content": "wiki 条目", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 4, "chunk_type": "wiki"}},
            {"content": "X 属于 Y", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 5, "chunk_type": "entity"}},
            {"content": "图中文字OCR", "metadata": {"source": "a.txt", "page": 2, "chunk_index": 200000, "chunk_type": "ocr"}},
            {"content": "图描述caption", "metadata": {"source": "a.txt", "page": 2, "chunk_index": 300000, "chunk_type": "image_caption"}},
            {"content": "父块", "metadata": {"source": "a.txt", "page": 1, "chunk_index": 6, "chunk_type": "parent", "is_parent": True}},
        ]
        self._run(self.store.store_chunks(
            [{"content": b["content"], "metadata": b["metadata"], "embedding": None} for b in blocks],
            "kb1", "doc1", "t1",
        ))
        pages = self._run(self.store.get_document_pages("doc1"))
        joined = "".join(p["text"] for p in pages)
        self.assertIn("原文块1", joined)
        for noise in ("Q: x A: y", "推测问题?", "文档摘要", "wiki 条目", "X 属于 Y", "图中文字OCR", "图描述caption", "父块"):
            self.assertNotIn(noise, joined)


if __name__ == "__main__":
    unittest.main()
