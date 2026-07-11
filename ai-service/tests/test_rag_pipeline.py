"""文档处理全链路 + RAG 检索集成测试（内存存储，零外部依赖）。"""

import asyncio
import os
import tempfile
import unittest

import services.document_processor as dp_mod
import services.vector_store as vs_mod
from services.document_processor import document_processor
from services.rag import rag_service
from services.vector_store import InMemoryVectorStore


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
            cnt = self._run(
                document_processor.process(path, "txt", "kb1", "doc1", "t1")
            )
            self.assertGreater(cnt, 0)

            results = self._run(
                rag_service.retrieve("熊答 知识问答 助手", ["kb1"], "t1", top_n=3)
            )
            self.assertTrue(len(results) >= 1)
            self.assertTrue(any("熊答" in r.content for r in results))
            # 检索结果应携带来源元数据
            self.assertTrue(all(r.source.endswith(".txt") for r in results))
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
