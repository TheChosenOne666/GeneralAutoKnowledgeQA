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
            self.assertGreater(cnt, 0)

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


if __name__ == "__main__":
    unittest.main()
