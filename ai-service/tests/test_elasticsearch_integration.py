"""Elasticsearch 集成测试（M5-8，可选）。

仅当满足以下条件时运行：
- 环境变量 ``ES_TEST_URL`` 指向一个可用的 ES 8.x 实例（如 ``http://localhost:9200``）；
- ``elasticsearch`` 客户端已安装。
否则整体 skip，CI 无 ES 时不阻塞（与单测守卫一致）。
"""

import asyncio
import os
import unittest

try:
    import elasticsearch  # noqa: F401

    _ES_AVAILABLE = True
except ImportError:
    _ES_AVAILABLE = False

from core.config import settings  # noqa: E402

ES_TEST_URL = os.environ.get("ES_TEST_URL")
_SKIP = not (ES_TEST_URL and _ES_AVAILABLE)


@unittest.skipIf(_SKIP, "ES_TEST_URL 未设置或 elasticsearch 未安装，跳过 ES 集成测试")
class ElasticsearchIntegrationTest(unittest.TestCase):
    """端到端：index → keyword_search → delete。"""

    @classmethod
    def setUpClass(cls):
        settings.elasticsearch_enabled = True
        settings.elasticsearch_hosts = ES_TEST_URL
        settings.elasticsearch_index_prefix = "xiongda_itest"
        from services.elasticsearch_store import close_es, get_es_store

        cls._close = close_es
        # AsyncElasticsearch 的 aiohttp 会话绑定到首次请求所在的 event loop；
        # 若每次 _run 用 asyncio.run() 新建并关闭 loop，第二次请求会报
        # "Event loop is closed"。故整个测试类复用同一个持久 loop。
        cls.loop = asyncio.new_event_loop()
        cls.store = get_es_store()
        asyncio.run(cls.store.reindex_from_pg()) if False else None  # 不自动迁移，隔离测试

    @classmethod
    def tearDownClass(cls):
        cls.loop.run_until_complete(cls._close())
        cls.loop.close()

    def _run(self, coro):
        return self.loop.run_until_complete(coro)

    def test_store_search_delete(self):
        chunks = [{
            "content": "熊答 企业 知识库 问答 助手",
            "metadata": {"chunk_type": "child", "chunk_index": 0,
                         "doc_id": "it_doc1", "kb_id": "it_kb", "tenant_id": "it_tenant"},
        }]
        self._run(self.store.index_chunks(chunks, "it_kb", "it_doc1", "it_tenant"))
        res = self._run(self.store.keyword_search("知识库 问答", ["it_kb"], "it_tenant", top_k=5))
        self.assertTrue(len(res) >= 1)
        self.assertIn("知识库", res[0].content)
        # 删除后无召回
        self._run(self.store.delete_by_doc("it_doc1"))
        res2 = self._run(self.store.keyword_search("知识库 问答", ["it_kb"], "it_tenant", top_k=5))
        self.assertEqual(res2, [])


if __name__ == "__main__":
    unittest.main()
