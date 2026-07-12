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

from services.embedding import EmbeddingService, get_redis
from services.model_config import ModelConfig, ModelConfigError


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

    def test_embed_batch_dimension_check(self):
        cfg = self._cfg(dim=8)
        client = self._client([[0.1, 0.2, 0.3, 0.4], [0.5, 0.6, 0.7, 0.8]])
        with self.assertRaises(ModelConfigError):
            asyncio.run(self.svc.embed_batch(["a", "b"], cfg, client))


if __name__ == "__main__":
    unittest.main()
