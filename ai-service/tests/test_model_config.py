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
from services.vector_store import InMemoryVectorStore


class ModelConfigErrorTest(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryVectorStore()
        vs_mod.vector_store_service = self.store
        dp_mod.vector_store_service = self.store
        self.client = TestClient(app)

    def test_document_process_model_config_error(self):
        """无 Embedding API Key → 文档处理返回可识别的模型配置错误。"""
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

        self.assertEqual(data["status"], "failed")
        self.assertEqual(data["error_type"], "MODEL_CONFIG_ERROR")
        self.assertIn("Embedding", data["error"])

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


if __name__ == "__main__":
    unittest.main()
