"""文档处理全链路 + RAG 检索集成测试（内存存储，零外部依赖）。

M3-3 取消静默降级后，Embedding 调用需配置 Key；此处用桩替换 embed_batch / embed_text，
聚焦「提取 → 分块 → 向量化 → 存储 → 检索命中」全链路，不依赖真实模型 Key。
"""

import asyncio
import os
import tempfile
import unittest
from unittest.mock import patch

import services.document_processor as dp_mod
import services.vector_store as vs_mod
from services.document_processor import document_processor
from services.rag import rag_service
from services.vector_store import InMemoryVectorStore

VEC = [0.1, 0.2, 0.3, 0.4]  # 固定 4 维向量


async def _fake_embed_text(text, cfg=None, client=None):
    return list(VEC)


async def _fake_embed_batch(texts, cfg=None, client=None):
    return [list(VEC) for _ in texts]


class RagPipelineTest(unittest.TestCase):
    def setUp(self):
        # 注入隔离的内存 store，避免污染全局单例
        self.store = InMemoryVectorStore()
        vs_mod.vector_store_service = self.store
        dp_mod.vector_store_service = self.store

    def _run(self, coro):
        return asyncio.run(coro)

    def test_process_and_retrieve(self):
        text = (
            "熊答是一款企业知识问答助手。\n"
            "它支持上传文档并自动进行向量化处理。\n"
            "用户可以通过自然语言提问获取知识库中的答案。"
        )
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write(text)
            path = f.name
        try:
            with patch(
                "services.embedding.embedding_service.embed_batch", _fake_embed_batch
            ):
                cnt = self._run(
                    document_processor.process(path, "txt", "kb1", "doc1", "t1")
                )
            self.assertGreater(cnt["chunk_count"], 0)

            with patch(
                "services.embedding.embedding_service.embed_text", _fake_embed_text
            ):
                results = self._run(
                    rag_service.retrieve("熊答 知识问答 助手", ["kb1"], "t1", top_n=3)
                )
            self.assertTrue(len(results) >= 1)
            self.assertTrue(any("熊答" in r.content for r in results))
            # 检索结果应携带来源元数据
            self.assertTrue(all(r.source.endswith(".txt") for r in results))
        finally:
            os.unlink(path)

    def test_unrelated_query_returns_empty(self):
        """完全无关的话题应被相关性门槛拦截，返回空（不展示错误引用来源）。"""
        text = "熊答是一款企业知识问答助手，支持上传文档并自动向量化处理。"
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write(text)
            path = f.name
        try:
            # 文档向量固定为 A，无关 query 向量固定为几乎正交的 B（余弦=0）
            async def fake_batch(texts, cfg=None, client=None):
                return [[1.0, 0.0, 0.0, 0.0] for _ in texts]

            async def fake_text(q, cfg=None, client=None):
                return [0.0, 1.0, 0.0, 0.0]

            with patch(
                "services.embedding.embedding_service.embed_batch", fake_batch
            ):
                self._run(
                    document_processor.process(path, "txt", "kb1", "doc1", "t1")
                )
            with patch(
                "services.embedding.embedding_service.embed_text", fake_text
            ):
                results = self._run(
                    rag_service.retrieve("招聘财务报销流程", ["kb1"], "t1", top_n=3)
                )
            self.assertEqual(results, [])
        finally:
            os.unlink(path)

    def test_is_relevant_logic(self):
        """相关性判定：向量/BM25/rerank 三种门槛逻辑。"""
        from services.vector_store import RetrievalResult

        # 无 rerank：向量超门槛 → 相关
        self.assertTrue(
            rag_service._is_relevant(
                [RetrievalResult(content="x", source="s", score=0.5)], False, 0.5, 0.0
            )
        )
        # 无 rerank：向量与 BM25 均低于门槛 → 不相关
        self.assertFalse(
            rag_service._is_relevant(
                [RetrievalResult(content="x", source="s", score=0.1)], False, 0.1, 0.0
            )
        )
        # 无 rerank：BM25 超门槛 → 相关（关键词强相关）
        self.assertTrue(
            rag_service._is_relevant(
                [RetrievalResult(content="x", source="s", score=0.1)], False, 0.1, 2.0
            )
        )
        # 有 rerank：分数低于门槛 → 不相关
        self.assertFalse(
            rag_service._is_relevant(
                [RetrievalResult(content="x", source="s", score=0.05)], True, 0.5, 0.0
            )
        )
        # 有 rerank：分数超门槛 → 相关
        self.assertTrue(
            rag_service._is_relevant(
                [RetrievalResult(content="x", source="s", score=0.5)], True, 0.1, 0.0
            )
        )


if __name__ == "__main__":
    unittest.main()
