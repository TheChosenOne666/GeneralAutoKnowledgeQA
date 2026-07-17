"""M3-3 模型配置错误识别与透传测试。

验证：
- 文档处理无 Embedding Key → 返回 error_type=MODEL_CONFIG_ERROR
- 对话无 LLM Key → SSE 推送 event: error（error_type=MODEL_CONFIG_ERROR）
取消静默降级后，不再造假向量 / 假回答。
"""

import asyncio
import os
import tempfile
import unittest

from fastapi.testclient import TestClient

import services.document_processor as dp_mod
import services.vector_store as vs_mod
from main import app
from services.document_processor import document_processor
from services.model_config import ModelConfigError, ModelQuotaError
from services.vector_store import InMemoryVectorStore


class ModelConfigErrorTest(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryVectorStore()
        vs_mod.vector_store_service = self.store
        dp_mod.vector_store_service = self.store
        self.client = TestClient(app)

    def test_document_process_model_config_error(self):
        """无 Embedding API Key → 主流程入队成功、接口立即返回 processing（M5-1 异步化契约）。

        模型配置错误的分类（MODEL_CONFIG_ERROR）发生在常驻 worker 内，由
        tests/test_process_queue.py::test_worker_notifies_failed_on_model_config_error 覆盖；
        此处仅校验 HTTP 入口只负责入队、不阻塞、立即返回 processing。
        """
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write("测试文档内容用于向量化。")
            path = f.name
        try:
            resp = self.client.post(
                "/ai/document/process",
                json={
                    "doc_id": "d1",
                    "file_path": path,
                    "file_type": "txt",
                    "kb_id": "kb1",
                    "tenant_id": "t1",
                    "ai_config": {"embedding_api_key": "", "llm_api_key": ""},
                },
            )
            data = resp.json()
        finally:
            os.unlink(path)

        self.assertEqual(data["status"], "processing")
        self.assertEqual(data["doc_id"], "d1")

    def test_document_process_quota_error(self):
        """Embedding 被限流（ModelQuotaError）→ 主流程入队成功、接口立即返回 processing（M5-1 异步化契约）。

        限流 / 额度错误的分类（MODEL_QUOTA_ERROR）发生在常驻 worker 内，由
        tests/test_process_queue.py 的 worker 失败回调覆盖；此处仅校验 HTTP 入口
        只负责入队、不阻塞、立即返回 processing。
        """
        from unittest.mock import patch

        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write("测试文档内容用于向量化。")
            path = f.name
        try:
            with patch(
                "services.embedding.embedding_service.embed_batch",
                side_effect=ModelQuotaError("Embedding 调用被限流（HTTP 429）"),
            ):
                resp = self.client.post(
                    "/ai/document/process",
                    json={
                        "doc_id": "d2",
                        "file_path": path,
                        "file_type": "txt",
                        "kb_id": "kb1",
                        "tenant_id": "t1",
                        "ai_config": {
                            "embedding_api_key": "k",
                            "embedding_model": "m",
                            "embedding_dimension": 4,
                            "llm_api_key": "k",
                        },
                    },
                )
                data = resp.json()
        finally:
            os.unlink(path)

        self.assertEqual(data["status"], "processing")
        self.assertEqual(data["doc_id"], "d2")

    def test_chat_stream_model_config_error(self):
        """无 LLM API Key（search 模式跳过检索）→ SSE 推送 event: error。"""
        with self.client.stream(
            "POST",
            "/ai/chat/stream",
            json={
                "question": "你好",
                "tenant_id": "t1",
                "mode": "search",
                "ai_config": {"llm_api_key": ""},
            },
        ) as resp:
            body = "".join(resp.iter_text())

        self.assertIn("event: error", body)
        self.assertIn("MODEL_CONFIG_ERROR", body)
        self.assertIn("event: done", body)

    def test_chat_stream_quota_error(self):
        """LLM 被限流 / 额度不足（ModelQuotaError）→ SSE 推送 event: error（QUOTA_ERROR）。"""
        from unittest.mock import patch

        with patch(
            "services.llm.llm_service.stream_generate",
            side_effect=ModelQuotaError("LLM 调用被限流（HTTP 429）"),
        ):
            with self.client.stream(
                "POST",
                "/ai/chat/stream",
                json={
                    "question": "你好",
                    "tenant_id": "t1",
                    "mode": "search",
                    "ai_config": {"llm_api_key": "k"},
                },
            ) as resp:
                body = "".join(resp.iter_text())

        self.assertIn("event: error", body)
        self.assertIn("QUOTA_ERROR", body)
        self.assertIn("event: done", body)


if __name__ == "__main__":
    unittest.main()
