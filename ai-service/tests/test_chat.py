"""聊天路由单元测试：RAG 模式下推送 sources 事件 + token + done。

M3-3 取消静默降级后，Embedding / LLM 调用需配置 Key；此处用桩替换服务方法，
聚焦 SSE 事件协议（sources / token / done）与多轮历史，不依赖真实模型 Key。
"""

import asyncio
import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import services.document_processor as dp_mod
import services.vector_store as vs_mod
from core.config import settings
from main import app
from services.document_processor import document_processor
from services.llm import llm_service
from services.vector_store import InMemoryVectorStore

VEC = [0.1, 0.2, 0.3, 0.4]  # 固定 4 维向量，保证检索可命中


async def _fake_embed_text(text, cfg=None, client=None):
    return list(VEC)


async def _fake_embed_batch(texts, cfg=None, client=None):
    return [list(VEC) for _ in texts]


async def _fake_stream_generate(
    question, context="", model=None, history=None, cfg=None, client=None,
    no_kb_content=False, context_source="kb",
):
    yield "熊"
    yield "答"


async def _fake_complete(messages, model=None, cfg=None, client=None):
    """改写视为原话（no-op），供 enhance=True 路径在单测中不触发真实 LLM 调用。"""
    return messages[-1].get("content", "")


async def _fake_retrieve_empty(query, kb_ids, tenant_id, top_n=5, cfg=None):
    return []


class _SpyStreamGenerate:
    """记录 stream_generate 调用参数，用于校验兜底分支。"""

    def __init__(self):
        self.calls = 0
        self.kwargs = None

    async def __call__(
        self, question, context="", model=None, history=None, cfg=None,
        client=None, no_kb_content=False, context_source="kb",
    ):
        self.calls += 1
        self.kwargs = dict(
            question=question,
            context=context,
            no_kb_content=no_kb_content,
            context_source=context_source,
        )
        yield "熊"
        yield "答"



class ChatRagTest(unittest.TestCase):
    def setUp(self):
        # 注入隔离的内存 store，避免污染全局单例
        self.store = InMemoryVectorStore()
        vs_mod.vector_store_service = self.store
        dp_mod.vector_store_service = self.store
        self.client = TestClient(app)

    def _patch_services(self):
        return patch.multiple(
            "services.embedding.embedding_service",
            embed_text=_fake_embed_text,
            embed_batch=_fake_embed_batch,
        )

    def test_chat_stream_emits_sources_and_tokens(self):
        text = "熊答是一款企业知识问答助手，支持文档向量化与检索。"
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write(text)
            path = f.name
        # 桩替换 Embedding / LLM（含改写 complete），避免真实模型 Key 依赖；
        # 覆盖文档处理与流式问答两阶段
        with self._patch_services(), patch(
            "services.llm.llm_service.stream_generate", _fake_stream_generate
        ), patch("services.llm.llm_service.complete", _fake_complete):
            asyncio.run(document_processor.process(path, "txt", "kb1", "doc1", "t1"))
            os.unlink(path)

            with self.client.stream(
                "POST",
                "/ai/chat/stream",
                json={
                    "question": "熊答是什么",
                    "kb_ids": ["kb1"],
                    "tenant_id": "t1",
                    "mode": "rag",
                },
            ) as resp:
                body = "".join(resp.iter_text())

        self.assertIn("event: sources", body)
        self.assertIn("event: token", body)
        self.assertIn("event: done", body)
        self.assertIn("doc1", body)
        self.assertIn("熊答", body)

    def test_chat_stream_with_history(self):
        text = "熊答是一款企业知识问答助手。"
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write(text)
            path = f.name
        history = [
            {"role": "user", "content": "你好"},
            {"role": "assistant", "content": "你好！我是熊答。"},
        ]
        with self._patch_services(), patch(
            "services.llm.llm_service.stream_generate", _fake_stream_generate
        ), patch("services.llm.llm_service.complete", _fake_complete):
            asyncio.run(document_processor.process(path, "txt", "kb1", "doc1", "t1"))
            os.unlink(path)

            with self.client.stream(
                "POST",
                "/ai/chat/stream",
                json={
                    "question": "熊答是什么",
                    "kb_ids": ["kb1"],
                    "tenant_id": "t1",
                    "mode": "rag",
                    "history": history,
                },
            ) as resp:
                body = "".join(resp.iter_text())

        self.assertIn("event: token", body)
        self.assertIn("event: done", body)

    def test_chat_stream_empty_kb_fixed_fallback(self):
        """检索无结果且 fallback_strategy=fixed：直接返回固定文案，且不调用 LLM。"""
        saved = settings.fallback_strategy
        settings.fallback_strategy = "fixed"
        spy = _SpyStreamGenerate()
        try:
            with self._patch_services(), patch(
                "services.rag.rag_service.retrieve", _fake_retrieve_empty
            ), patch("services.llm.llm_service.stream_generate", spy):
                with self.client.stream(
                    "POST",
                    "/ai/chat/stream",
                    json={
                        "question": "知识库里没有的话题",
                        "kb_ids": ["kb1"],
                        "tenant_id": "t1",
                        "mode": "rag",
                    },
                ) as resp:
                    body = "".join(resp.iter_text())
        finally:
            settings.fallback_strategy = saved

        self.assertEqual(spy.calls, 0, "fixed 兜底不应调用 LLM")
        self.assertIn("event: token", body)
        self.assertIn(settings.fallback_response, body)
        self.assertIn("event: done", body)

    def test_chat_stream_empty_kb_model_fallback(self):
        """检索无结果且 fallback_strategy=model：调用 LLM 并注入 no_kb_content 声明。"""
        saved = settings.fallback_strategy
        settings.fallback_strategy = "model"
        spy = _SpyStreamGenerate()
        try:
            with self._patch_services(), patch(
                "services.rag.rag_service.retrieve", _fake_retrieve_empty
            ), patch("services.llm.llm_service.stream_generate", spy):
                with self.client.stream(
                    "POST",
                    "/ai/chat/stream",
                    json={
                        "question": "知识库里没有的话题",
                        "kb_ids": ["kb1"],
                        "tenant_id": "t1",
                        "mode": "rag",
                    },
                ) as resp:
                    body = "".join(resp.iter_text())
        finally:
            settings.fallback_strategy = saved

        self.assertEqual(spy.calls, 1, "model 兜底应调用 LLM 一次")
        self.assertTrue(spy.kwargs["no_kb_content"], "应标记知识库无内容")
        self.assertEqual(spy.kwargs["context"], "", "无相关文档应无上下文")
        self.assertIn("event: token", body)
        self.assertIn("event: done", body)


if __name__ == "__main__":
    unittest.main()
