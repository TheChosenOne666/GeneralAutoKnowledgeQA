"""Embedding 服务单元测试（降级伪向量路径，零外部依赖）。"""

import asyncio
import math
import unittest

from services.embedding import EmbeddingService


class EmbeddingServiceTest(unittest.TestCase):
    def setUp(self):
        self.svc = EmbeddingService()

    def test_dimension(self):
        v = self.svc._embed_fallback("hello world")
        self.assertEqual(len(v), 1536)

    def test_deterministic(self):
        a = self.svc._embed_fallback("熊答知识库")
        b = self.svc._embed_fallback("熊答知识库")
        self.assertEqual(a, b)

    def test_different_texts_differ(self):
        a = self.svc._embed_fallback("人工智能")
        b = self.svc._embed_fallback("足球比赛")
        self.assertNotEqual(a, b)

    def test_normalized(self):
        v = self.svc._embed_fallback("test normalization")
        self.assertAlmostEqual(math.sqrt(sum(x * x for x in v)), 1.0, places=5)

    def test_embed_text_async_fallback(self):
        v = asyncio.run(self.svc.embed_text("hello"))
        self.assertEqual(len(v), 1536)

    def test_embed_batch_async(self):
        vs = asyncio.run(self.svc.embed_batch(["a", "b"]))
        self.assertEqual(len(vs), 2)
        self.assertEqual(len(vs[0]), 1536)


if __name__ == "__main__":
    unittest.main()
