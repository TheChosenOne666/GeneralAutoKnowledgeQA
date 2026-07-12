"""Redis 三层缓存单元测试（L1 检索结果 / L2 嵌入向量 / 失效接口）。

使用 fakeredis 模拟 Redis，不依赖外部实例。需安装测试依赖：fakeredis
（pip install fakeredis）。
"""

import asyncio
import unittest
from unittest.mock import AsyncMock

import fakeredis.aioredis

from routers import cache as cache_router
from routers.cache import InvalidateRequest
from services import vector_store as vector_store_module
from services.embedding import embedding_service
from services.model_config import ModelConfig
from services.rag import rag_service


class _FakeRedisTest(unittest.TestCase):
    def setUp(self):
        self.fake = fakeredis.aioredis.FakeRedis(decode_responses=True)
        # 调用方以 `from core.redis_client import get_redis` 方式持有拷贝引用，
        # 直接覆盖模块内的 get_redis 名称才能让测试走 fakeredis 实例。
        import routers.cache as cache_mod
        import services.embedding as emb_mod
        import services.rag as rag_mod

        for mod in (emb_mod, rag_mod, cache_mod):
            mod.get_redis = AsyncMock(return_value=self.fake)

    def tearDown(self):
        self._run(self.fake.flushall())

    def _run(self, coro):
        return asyncio.run(coro)


class EmbeddingCacheTest(_FakeRedisTest):
    """L2 嵌入向量缓存。"""

    def _patch_remote(self):
        """用计数桩替换远程调用，避免真实 HTTP 且绕过无 Key 校验。"""
        call_count = {"n": 0}
        orig = embedding_service._embed_remote

        async def counting(texts, cfg, client=None):
            call_count["n"] += 1
            return [[0.1, 0.2, 0.3, 0.4] for _ in texts]

        embedding_service._embed_remote = counting
        cfg = ModelConfig(embedding_api_key="k", embedding_model="m", embedding_dimension=4)
        return call_count, cfg, orig

    def test_embed_text_cache_hit(self):
        call_count, cfg, orig = self._patch_remote()
        try:
            v1 = self._run(embedding_service.embed_text("缓存测试文本", cfg))
            v2 = self._run(embedding_service.embed_text("缓存测试文本", cfg))
        finally:
            embedding_service._embed_remote = orig

        # 只计算一次：第二次命中 L2 缓存，不再调用远程
        self.assertEqual(call_count["n"], 1)
        self.assertEqual(v1, v2)
        keys = self._run(self.fake.keys("embedding:*"))
        self.assertEqual(len(keys), 1)

    def test_embed_batch_cache_hit(self):
        call_count, cfg, orig = self._patch_remote()
        try:
            v1 = self._run(embedding_service.embed_batch(["A", "B"], cfg))
            v2 = self._run(embedding_service.embed_batch(["A", "B"], cfg))
        finally:
            embedding_service._embed_remote = orig

        self.assertEqual(call_count["n"], 1)  # 批量一次远程调用
        self.assertEqual(v1, v2)
        keys = self._run(self.fake.keys("embedding:*"))
        self.assertEqual(len(keys), 2)


class RagCacheTest(_FakeRedisTest):
    """L1 检索结果缓存。"""

    def setUp(self):
        super().setUp()
        # retrieve 现需 Embedding 配置；用固定向量桩绕开 Key 校验，聚焦 L1 缓存行为
        import services.embedding as emb_mod
        from services.model_config import ModelConfig

        async def fake_embed_text(text, cfg=None, client=None):
            return [0.1, 0.2, 0.3, 0.4]

        emb_mod.embedding_service.embed_text = fake_embed_text

    def test_retrieve_cache_hit_skips_retrieval(self):
        call_count = {"n": 0}
        orig_search = vector_store_module.vector_store_service.search
        orig_kw = vector_store_module.vector_store_service.keyword_search

        async def fake_search(*args, **kwargs):
            call_count["n"] += 1
            return []

        async def fake_kw(*args, **kwargs):
            return []

        vector_store_module.vector_store_service.search = fake_search
        vector_store_module.vector_store_service.keyword_search = fake_kw
        try:
            r1 = self._run(rag_service.retrieve("相同问题", ["kb1"], "t1"))
            r2 = self._run(rag_service.retrieve("相同问题", ["kb1"], "t1"))
        finally:
            vector_store_module.vector_store_service.search = orig_search
            vector_store_module.vector_store_service.keyword_search = orig_kw

        # 检索只执行一次：第二次命中 L1 缓存，跳过向量/BM25/RRF/Rerank
        self.assertEqual(call_count["n"], 1)
        self.assertEqual(r1, r2)
        keys = self._run(self.fake.keys("retrieval:*"))
        self.assertEqual(len(keys), 1)

    def test_different_tenant_not_shared(self):
        # 不同 tenant 视为不同缓存，互不命中
        self._run(rag_service.retrieve("问题", ["kb1"], "t1"))
        self._run(rag_service.retrieve("问题", ["kb1"], "t2"))
        keys = self._run(self.fake.keys("retrieval:*"))
        self.assertEqual(len(keys), 2)


class CacheInvalidateTest(_FakeRedisTest):
    """文档变更时主动失效 L1 检索缓存（tenant 级）。"""

    def test_invalidate_clears_tenant_retrieval(self):
        self._run(self.fake.set("retrieval:t1:abc", "x"))
        self._run(self.fake.set("retrieval:t2:abc", "y"))
        res = self._run(cache_router.invalidate(InvalidateRequest(tenant_id="t1")))
        self.assertEqual(res["deleted"], 1)
        remaining = self._run(self.fake.keys("retrieval:*"))
        self.assertEqual(remaining, ["retrieval:t2:abc"])


if __name__ == "__main__":
    unittest.main()
