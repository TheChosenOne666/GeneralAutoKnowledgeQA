"""Embedding 服务单元测试（取消静默降级后）。

- 有 Key：调用远程接口（httpx MockTransport 注入），校验维度与缓存。
- 无 Key：抛出 ModelConfigError（不再造假向量）。
- 维度不匹配：抛出 ModelConfigError。
- 远程调用失败（Key/模型名错误）：抛出 ModelConfigError。

注意：隔离 Redis（patch get_redis），避免共享 Redis 中残留的缓存向量干扰断言。
"""

import asyncio
import unittest
from unittest.mock import AsyncMock

import httpx
import json

from services.embedding import EmbeddingService, get_redis
from services.model_config import ModelConfig, ModelConfigError, ModelQuotaError


class EmbeddingServiceTest(unittest.TestCase):
    def setUp(self):
        self.svc = EmbeddingService()
        # 隔离 Redis：任意 get_redis 调用直接抛异常，使缓存读取/写入走「降级」分支
        self._orig_get_redis = get_redis
        embedding_mod = __import__("services.embedding", fromlist=["get_redis"])
        embedding_mod.get_redis = AsyncMock(side_effect=Exception("redis isolated in test"))

    def tearDown(self):
        embedding_mod = __import__("services.embedding", fromlist=["get_redis"])
        embedding_mod.get_redis = self._orig_get_redis

    def _client(self, vectors):
        handler = lambda request: httpx.Response(
            200, json={"data": [{"embedding": v, "index": i} for i, v in enumerate(vectors)]}
        )
        return httpx.AsyncClient(transport=httpx.MockTransport(handler))

    def _cfg(self, dim=4, key="test-key"):
        return ModelConfig(embedding_api_key=key, embedding_model="m", embedding_dimension=dim)

    def test_remote_embed_text_dimension(self):
        cfg = self._cfg(dim=4)
        client = self._client([[0.1, 0.2, 0.3, 0.4]])
        v = asyncio.run(self.svc.embed_text("hello", cfg, client))
        self.assertEqual(len(v), 4)

    def test_remote_embed_batch(self):
        cfg = self._cfg(dim=2)
        client = self._client([[0.1, 0.2], [0.3, 0.4]])
        vs = asyncio.run(self.svc.embed_batch(["a", "b"], cfg, client))
        self.assertEqual(len(vs), 2)
        self.assertEqual(len(vs[0]), 2)

    def test_no_api_key_raises(self):
        cfg = ModelConfig(embedding_api_key="")  # 无 Key
        with self.assertRaises(ModelConfigError):
            asyncio.run(self.svc.embed_text("hello", cfg))

    def test_dimension_mismatch_raises(self):
        cfg = self._cfg(dim=8)  # 期望 8 维
        client = self._client([[0.1, 0.2, 0.3, 0.4]])  # 实际返回 4 维
        with self.assertRaises(ModelConfigError):
            asyncio.run(self.svc.embed_text("hello", cfg, client))

    def test_remote_failure_raises(self):
        cfg = self._cfg(dim=2)
        handler = lambda request: httpx.Response(401, json={"error": "invalid api key"})
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with self.assertRaises(ModelConfigError):
            asyncio.run(self.svc.embed_text("hello", cfg, client))

    def test_429_raises_quota_error(self):
        """持续 429（限流 / 额度不足）→ 抛 ModelQuotaError（而非 ModelConfigError）。"""
        cfg = self._cfg(dim=2)

        def handler(request):
            return httpx.Response(429, json={"error": "rate limit exceeded"})

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with self.assertRaises(ModelQuotaError):
            asyncio.run(self.svc.embed_text("hello", cfg, client))

    def test_4xx_quota_keyword_raises_quota_error(self):
        """4xx 但响应体含额度关键词（quota / insufficient balance）→ 抛 ModelQuotaError。"""
        cfg = self._cfg(dim=2)
        handler = lambda request: httpx.Response(
            403, json={"error": "quota exceeded, insufficient balance"}
        )
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with self.assertRaises(ModelQuotaError):
            asyncio.run(self.svc.embed_text("hello", cfg, client))

    def test_embed_batch_dimension_check(self):
        cfg = self._cfg(dim=8)
        client = self._client([[0.1, 0.2, 0.3, 0.4], [0.5, 0.6, 0.7, 0.8]])
        with self.assertRaises(ModelConfigError):
            asyncio.run(self.svc.embed_batch(["a", "b"], cfg, client))

    def _client_with_capture(self, captured):
        """记录每次请求 path/body 的 Mock 客户端；多模态端点返回对象结构，
        标准端点返回数组结构（对齐火山方舟真实响应）。"""
        vec = [0.1, 0.2, 0.3, 0.4]

        def handler(request):
            captured.setdefault("requests", []).append(
                {"path": request.url.path, "body": json.loads(request.content)}
            )
            if "multimodal" in request.url.path:
                return httpx.Response(200, json={"data": {"embedding": vec}})
            return httpx.Response(200, json={"data": [{"embedding": vec, "index": 0}]})

        return httpx.AsyncClient(transport=httpx.MockTransport(handler))

    def test_multimodal_embed_text_endpoint_and_format(self):
        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_provider="火山方舟",
            embedding_model="doubao-embedding-vision-251215",
            embedding_base_url="https://ark.example.com/api/v3",
            embedding_dimension=4,
        )
        captured: dict = {}
        client = self._client_with_capture(captured)
        v = asyncio.run(self.svc.embed_text("hello", cfg, client))
        self.assertEqual(len(v), 4)
        self.assertEqual(len(captured["requests"]), 1)
        req = captured["requests"][0]
        self.assertEqual(req["path"], "/api/v3/embeddings/multimodal")
        self.assertEqual(req["body"]["input"], [{"type": "text", "text": "hello"}])
        self.assertEqual(req["body"]["dimensions"], 4)

    def test_multimodal_embed_batch_endpoint_and_format(self):
        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_provider="火山方舟",
            embedding_model="doubao-embedding-vision-251215",
            embedding_base_url="https://ark.example.com/api/v3",
            embedding_dimension=4,
        )
        captured: dict = {}
        client = self._client_with_capture(captured)
        vs = asyncio.run(self.svc.embed_batch(["a", "b"], cfg, client))
        self.assertEqual(len(vs), 2)
        self.assertEqual(len(captured["requests"]), 2)
        for req in captured["requests"]:
            self.assertEqual(req["path"], "/api/v3/embeddings/multimodal")
            self.assertEqual(len(req["body"]["input"]), 1)
            self.assertEqual(req["body"]["input"][0]["type"], "text")
            self.assertEqual(req["body"]["dimensions"], 4)

    def test_standard_embedding_endpoint_and_format(self):
        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_provider="OpenAI",
            embedding_model="text-embedding-3-small",
            embedding_base_url="https://ark.example.com/api/v3",
            embedding_dimension=4,
        )
        captured: dict = {}
        client = self._client_with_capture(captured)
        v = asyncio.run(self.svc.embed_text("hello", cfg, client))
        self.assertEqual(len(v), 4)
        self.assertEqual(captured["requests"][0]["path"], "/api/v3/embeddings")
        self.assertEqual(captured["requests"][0]["body"]["input"], ["hello"])
        self.assertEqual(captured["requests"][0]["body"]["dimensions"], 4)

    def test_standard_embedding_passes_dimensions(self):
        """标准（非多模态）Embedding 路径也应透传 dimensions。

        修复前标准路径不传 dimensions，导致模型返回默认维度（如阿里云百炼
        qwen3.7-text-embedding 默认 1024）与配置维度（如 1536）不符而报错。
        """
        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_provider="阿里云百炼",
            embedding_model="qwen3.7-text-embedding",
            embedding_base_url="https://dashscope.example.com/compatible-mode/v1",
            embedding_dimension=4,
        )
        captured: dict = {}
        client = self._client_with_capture(captured)
        v = asyncio.run(self.svc.embed_text("hello", cfg, client))
        self.assertEqual(len(v), 4)
        self.assertEqual(captured["requests"][0]["path"], "/compatible-mode/v1/embeddings")
        self.assertEqual(captured["requests"][0]["body"]["dimensions"], 4)

    def test_embedding_dimension_required(self):
        """未填写向量维度时，Embedding 调用必须直接报错（绝不回退默认值）。"""
        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_model="qwen3.7-text-embedding",
            embedding_base_url="https://dashscope.example.com/compatible-mode/v1",
            embedding_dimension=None,
        )
        client = self._client_with_capture({})
        with self.assertRaises(ModelConfigError):
            asyncio.run(self.svc.embed_text("hello", cfg, client))

    def test_multimodal_embedding_always_passes_dimensions(self):
        """多模态 Embedding 路径透传 dimensions（火山方舟在白名单内）。"""
        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_provider="火山方舟",
            embedding_model="doubao-embedding-vision",
            embedding_base_url="https://ark.example.com/api/v3",
            embedding_dimension=4,
        )
        captured: dict = {}
        client = self._client_with_capture(captured)
        v = asyncio.run(self.svc.embed_text("hello", cfg, client))
        self.assertEqual(len(v), 4)
        self.assertEqual(captured["requests"][0]["path"], "/api/v3/embeddings/multimodal")
        self.assertEqual(captured["requests"][0]["body"]["dimensions"], 4)

    def test_bge_embedding_does_not_pass_dimensions(self):
        """白名单外的提供商（如 BGE）不识别 dimensions 参数，必须不传、使用模型默认维度。"""
        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_provider="BGE",
            embedding_model="bge-m3",
            embedding_base_url="https://api.siliconflow.cn/v1",
            embedding_dimension=4,
        )
        captured: dict = {}
        client = self._client_with_capture(captured)
        v = asyncio.run(self.svc.embed_text("hello", cfg, client))
        self.assertEqual(len(v), 4)
        self.assertEqual(captured["requests"][0]["path"], "/v1/embeddings")
        self.assertNotIn("dimensions", captured["requests"][0]["body"])


    def test_standard_embedding_batches_large_input(self):
        """标准路径对超过单批上限的 input 自动分批（每批 ≤ EMBEDDING_BATCH_SIZE）。

        修复前整批发送会因百炼「batch size is invalid, it should not be larger than 20」
        而报 HTTP 400。此处用 25 条触发 2 批（20 + 5），校验分批次数、批大小上限与结果数量。
        """
        from services.embedding import EMBEDDING_BATCH_SIZE

        cfg = ModelConfig(
            embedding_api_key="k",
            embedding_provider="OpenAI",
            embedding_model="text-embedding-3-small",
            embedding_base_url="https://ark.example.com/api/v3",
            embedding_dimension=4,
        )
        n = 25
        captured: dict = {}

        def handler(request):
            body = json.loads(request.content)
            batch = body["input"]
            captured.setdefault("requests", []).append(batch)
            # 按当前批次 input 数量返回对应条数向量，每条带 index 保序
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"embedding": [0.1, 0.2, 0.3, 0.4], "index": i}
                        for i in range(len(batch))
                    ]
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        texts = [f"t{i}" for i in range(n)]
        vs = asyncio.run(self.svc.embed_batch(texts, cfg, client))
        self.assertEqual(len(vs), n)
        # 分批次数 = ceil(n / BATCH_SIZE)
        self.assertEqual(
            len(captured["requests"]), (n + EMBEDDING_BATCH_SIZE - 1) // EMBEDDING_BATCH_SIZE
        )
        for batch in captured["requests"]:
            self.assertLessEqual(len(batch), EMBEDDING_BATCH_SIZE)
        self.assertTrue(all(len(v) == 4 for v in vs))


if __name__ == "__main__":
    unittest.main()
