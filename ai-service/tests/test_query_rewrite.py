"""查询改写 / 扩展单元测试（对齐 WeKnora KnowledgeQA 的 query rewrite + expansion）。

M3-3 取消静默降级后，rewrite / expansion 须复用真实 LLM；此处用 AsyncMock 替换
``llm_service.complete``，聚焦改写 / 扩展的解析与失败降级逻辑，不依赖真实模型 Key。
"""

import asyncio
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

import services.document_processor as dp_mod
import services.vector_store as vs_mod
from services.document_processor import document_processor
from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError
from services.query_rewrite import expand_query, rewrite_query
from services.rag import rag_service
from services.vector_store import InMemoryVectorStore

VEC = [0.1, 0.2, 0.3, 0.4]


async def _fake_embed_text(text, cfg=None, client=None):
    return list(VEC)


async def _fake_embed_batch(texts, cfg=None, client=None):
    return [list(VEC) for _ in texts]


def _run(coro):
    return asyncio.run(coro)


class QueryRewriteTest(unittest.TestCase):
    def test_rewrite_success(self):
        with patch.object(
            llm_service, "complete", new=AsyncMock(return_value="算法工程师 入职 准备 设备 账号")
        ):
            out = _run(rewrite_query("算法入职前期准备要做什么", cfg=ModelConfig.from_settings()))
        self.assertEqual(out, "算法工程师 入职 准备 设备 账号")

    def test_rewrite_fallback_on_generic_error(self):
        async def boom(*a, **k):
            raise RuntimeError("llm down")

        with patch.object(llm_service, "complete", new=boom):
            out = _run(rewrite_query("原话问题", cfg=ModelConfig.from_settings()))
        self.assertEqual(out, "原话问题")

    def test_rewrite_propagates_model_config_error(self):
        async def boom(*a, **k):
            raise ModelConfigError("key 错误")

        with patch.object(llm_service, "complete", new=boom):
            with self.assertRaises(ModelConfigError):
                _run(rewrite_query("原话问题", cfg=ModelConfig.from_settings()))

    def test_expand_returns_list(self):
        with patch.object(
            llm_service,
            "complete",
            new=AsyncMock(return_value="算法岗 入职 设备\n算法工程师 入职 培训计划"),
        ):
            out = _run(expand_query("算法入职前期准备", cfg=ModelConfig.from_settings()))
        self.assertEqual(out, ["算法岗 入职 设备", "算法工程师 入职 培训计划"])

    def test_expand_empty_on_error(self):
        async def boom(*a, **k):
            raise RuntimeError("down")

        with patch.object(llm_service, "complete", new=boom):
            out = _run(expand_query("原话问题", cfg=ModelConfig.from_settings()))
        self.assertEqual(out, [])


class RetrieveEnhanceTest(unittest.TestCase):
    """retrieve(enhance=True) 集成：rewrite 生效 + 主检索召回不足时 expansion 兜底。"""

    def setUp(self):
        self.store = InMemoryVectorStore()
        vs_mod.vector_store_service = self.store
        dp_mod.vector_store_service = self.store

    def _seed(self, text):
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write(text)
            path = f.name
        with patch(
            "services.embedding.embedding_service.embed_batch", _fake_embed_batch
        ):
            self._run(
                document_processor.process(path, "txt", "kb1", "doc1", "t1")
            )
        return path

    def _run(self, coro):
        return asyncio.run(coro)

    def test_enhance_triggers_rewrite_and_recalls(self):
        """enhance=True 时改写被调用，且固定向量下检索命中、结果非空。"""
        path = self._seed(
            "一、入职基础准备：新员工需领取办公设备、申请系统账号、完成入职培训。"
        )
        try:
            with patch(
                "services.embedding.embedding_service.embed_text", _fake_embed_text
            ), patch.object(
                llm_service,
                "complete",
                new=AsyncMock(return_value="入职 基础准备 设备 账号 培训"),
            ) as mock_complete:
                results = self._run(
                    rag_service.retrieve(
                        "算法入职前期准备要做什么", ["kb1"], "t1", top_n=3, enhance=True
                    )
                )
            # 改写被调用（retrieve 内部调一次 complete 做 rewrite）
            self.assertTrue(mock_complete.called)
            self.assertTrue(len(results) >= 1)
            self.assertTrue(any("入职" in r.content for r in results))
        finally:
            import os

            os.unlink(path)

    def test_no_enhance_skips_rewrite(self):
        """enhance=False（Agent / 缓存命中路径）时不调改写，行为与旧实现一致。"""
        path = self._seed(
            "熊答是一款企业知识问答助手，支持上传文档并自动向量化处理。"
        )
        try:
            with patch(
                "services.embedding.embedding_service.embed_text", _fake_embed_text
            ), patch.object(
                llm_service, "complete", new=AsyncMock(return_value="不应被调用")
            ) as mock_complete:
                results = self._run(
                    rag_service.retrieve("熊答 知识问答 助手", ["kb1"], "t1", top_n=3)
                )
            self.assertFalse(mock_complete.called)
            self.assertTrue(len(results) >= 1)
        finally:
            import os

            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
