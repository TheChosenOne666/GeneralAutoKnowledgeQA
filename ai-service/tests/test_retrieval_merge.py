"""检索融合策略单元测试 — 向量主导 + 相对阈值 + 文档去重 + 空 source 回退。

不依赖真实模型 Key：用桩替换 embed_text，按文本主题返回固定向量，
聚焦「向量可用时语义主导、BM25 仅补充」的融合行为是否正确。
L1 检索缓存被禁用（get_redis 抛异常），避免 unittest 跨测试 redis loop 干扰。
"""

import asyncio
import json
import unittest
from unittest.mock import patch

from services.rag import rag_service
from services.vector_store import InMemoryVectorStore
from services.model_config import ModelConfig

import services.vector_store as vs_mod
from core.config import settings


def _vec_of(text: str) -> list[float]:
    """按内容主题返回向量：后端贴近 [1,0,0,0]，前端中等相似 [0.5,0.5,0,0]，其余无关。"""
    if "后端" in text:
        return [1.0, 0.0, 0.0, 0.0]
    if "前端" in text:
        return [0.5, 0.5, 0.0, 0.0]
    return [0.0, 0.0, 0.0, 0.0]


async def _embed_text(text, cfg=None, client=None):
    if "后端" in text:
        return [1.0, 0.0, 0.0, 0.0]
    return [0.0, 0.0, 0.0, 0.0]


class RetrievalMergeTest(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryVectorStore()
        vs_mod.vector_store_service = self.store
        # 禁用 L1 缓存，保证测试确定性
        self.redis_patch = patch(
            "services.rag.get_redis", side_effect=RuntimeError("no redis in test")
        )
        self.redis_patch.start()

    def tearDown(self):
        self.redis_patch.stop()

    def _run(self, coro):
        return asyncio.run(coro)

    def _seed(self, docs):
        """docs: [(doc_id, kb_id, tenant, source, [contents])]，自动赋不同 chunk_index。"""

        async def _do():
            for doc_id, kb_id, tenant, source, chunks in docs:
                items = [
                    {
                        "content": c,
                        "metadata": {"source": source, "chunk_index": i},
                        "embedding": _vec_of(c),
                    }
                    for i, c in enumerate(chunks)
                ]
                await self.store.store_chunks(items, kb_id, doc_id, tenant)

        self._run(_do())

    def test_vector_dominant_suppresses_bm25_noise(self):
        """向量可用时，问「后端」应主要返回后端文档，前端/HR 关键词噪声不进 top。"""
        self._seed([
            ("d_back", "kb1", "t1", "后端新员工入职指南.docx", [
                "后端开发需要掌握Java",
                "后端还要懂数据库和缓存",
                "后端服务部署与监控",
                "后端性能优化实践",
                "后端接口设计规范",
                "后端日志与排查",
            ]),
            ("d_front", "kb1", "t1", "前端开发新员工入职指南.docx", [
                "前端开发需要掌握React",
                "前端还要学CSS和打包",
            ]),
            ("d_hr", "kb1", "t1", "HR人力资源新员工入职指南.docx", [
                "HR负责办理入职手续",
                "HR管理考勤和社保",
            ]),
        ])
        with patch("services.embedding.embedding_service.embed_text", _embed_text):
            results = self._run(
                rag_service.retrieve("后端刚入职要干嘛", ["kb1"], "t1", top_n=5)
            )
        sources = [r.source for r in results]
        self.assertTrue(
            all("后端" in s for s in sources), f"应全为后端文档，实际: {sources}"
        )
        self.assertFalse(any("前端" in s for s in sources))
        self.assertFalse(any("HR" in s for s in sources))

    def test_doc_dedup_limit(self):
        """单文档块数不超过 retrieval_max_chunks_per_doc。"""
        from core.config import settings

        self._seed([
            ("d_back", "kb1", "t1", "后端新员工入职指南.docx", [
                f"后端相关内容 {i}" for i in range(10)
            ]),
        ])
        with patch("services.embedding.embedding_service.embed_text", _embed_text):
            with patch.object(settings, "retrieval_max_chunks_per_doc", 2):
                results = self._run(
                    rag_service.retrieve("后端刚入职要干嘛", ["kb1"], "t1", top_n=5)
                )
        back = [r for r in results if "后端" in r.source]
        self.assertLessEqual(len(back), 2)

    def test_bm25_fallback_when_vector_empty(self):
        """向量全为0（embed 返回零向量）时退化 BM25 兜底，仍能召回含关键词文档。"""
        self._seed([
            ("d_back", "kb1", "t1", "后端新员工入职指南.docx", ["后端开发需要掌握Java和数据库"]),
            ("d_front", "kb1", "t1", "前端开发新员工入职指南.docx", ["前端需要掌握React"]),
        ])

        async def zero_text(text, cfg=None, client=None):
            return [0.0, 0.0, 0.0, 0.0]

        with patch("services.embedding.embedding_service.embed_text", zero_text):
            results = self._run(
                rag_service.retrieve("后端刚入职要干嘛", ["kb1"], "t1", top_n=5)
            )
        self.assertTrue(any("后端" in r.source for r in results))

    def test_empty_source_fallback(self):
        """空 source 块回退到同文档非空 source，保证引用来源可读。"""
        async def _do():
            items = [
                {"content": "后端开发需要掌握Java", "metadata": {"source": "后端新员工入职指南.docx", "chunk_index": 0}, "embedding": [1.0, 0.0, 0.0, 0.0]},
                {"content": "后端服务部署与监控", "metadata": {"source": "", "chunk_index": 1}, "embedding": [1.0, 0.0, 0.0, 0.0]},
            ]
            await self.store.store_chunks(items, "kb1", "d_back", "t1")

        self._run(_do())
        with patch("services.embedding.embedding_service.embed_text", _embed_text):
            results = self._run(
                rag_service.retrieve("后端刚入职要干嘛", ["kb1"], "t1", top_n=5)
            )
        for r in results:
            self.assertTrue(r.source, f"source 不应为空: {r.content}")

    def test_bm25_tiebreak_promotes_backend(self):
        """语义平手（前后端向量分差<epsilon）时，BM25 命中「后端」应抬升后端文档。"""
        async def _embed(text, cfg=None, client=None):
            return [1.0, 0.0, 0.0, 0.0]

        async def _do():
            front = [
                {"content": "前端规范：组件命名", "metadata": {"source": "前端开发规范.docx", "chunk_index": i, "chunk_type": "original"}, "embedding": [0.99, 0.1, 0.0, 0.0]}
                for i in range(2)
            ]
            back = [
                {"content": "后端规范：接口设计", "metadata": {"source": "后端开发规范.docx", "chunk_index": i, "chunk_type": "original"}, "embedding": [0.98, 0.1, 0.0, 0.0]}
                for i in range(2)
            ]
            await self.store.store_chunks(front, "kb1", "d_front", "t1")
            await self.store.store_chunks(back, "kb1", "d_back", "t1")

        self._run(_do())
        with patch("services.embedding.embedding_service.embed_text", _embed):
            with patch.object(settings, "retrieval_bm25_tie_epsilon", 0.02), \
                 patch.object(settings, "retrieval_bm25_tie_boost", 0.10), \
                 patch.object(settings, "retrieval_bm25_tie_overlap_min", 0.25):
                results = self._run(
                    rag_service.retrieve("后端规范是什么", ["kb1"], "t1", top_n=5)
                )
        back = [r for r in results if "后端" in r.source]
        front = [r for r in results if "前端" in r.source]
        self.assertTrue(back, "语义平手时应由 BM25 抬升后端文档")
        self.assertGreaterEqual(
            len(back), len(front), f"后端应不弱于前端: back={len(back)} front={len(front)}"
        )

    def test_exclude_qa_blocks(self):
        """排除 QA 增强块（chunk_type=='qa'），原始块优先作为引用来源。"""
        async def _do():
            orig = [
                {"content": "后端开发需要掌握Java", "metadata": {"source": "后端新员工入职指南.docx", "chunk_index": 0, "chunk_type": "original"}, "embedding": [1.0, 0.0, 0.0, 0.0]},
                {"content": "后端服务部署与监控", "metadata": {"source": "后端新员工入职指南.docx", "chunk_index": 1, "chunk_type": "original"}, "embedding": [1.0, 0.0, 0.0, 0.0]},
            ]
            # QA 增强块语义相近但 chunk_type=qa、source 为空，应被排除
            qa = [
                {"content": "后端刚入职要干嘛？需掌握Java和数据库部署监控", "metadata": {"source": "", "chunk_index": 0, "chunk_type": "qa"}, "embedding": [1.0, 0.0, 0.0, 0.0]},
            ]
            await self.store.store_chunks(orig, "kb1", "d_back", "t1")
            await self.store.store_chunks(qa, "kb1", "d_qa", "t1")

        self._run(_do())
        with patch("services.embedding.embedding_service.embed_text", _embed_text):
            results = self._run(
                rag_service.retrieve("后端刚入职要干嘛", ["kb1"], "t1", top_n=5)
            )
        self.assertTrue(results, "应召回原始块")
        for r in results:
            self.assertNotEqual(r.chunk_type, "qa", f"不应包含 QA 块: {r.content}")
            self.assertTrue(r.source, f"引用来源应为非空原始块: {r.content}")

    def test_relative_threshold_drops_cross_topic(self):
        """相对相关性阈值剔除明显低于最优分的跨主题块（前端中等相似 0.707 < 0.8）。"""
        self._seed([
            ("d_back", "kb1", "t1", "后端新员工入职指南.docx", [
                "后端开发需要掌握Java",  # 余弦 1.0
                "后端服务部署与监控",
                "后端性能优化实践",
            ]),
            ("d_front", "kb1", "t1", "前端开发新员工入职指南.docx", [
                "前端需要掌握React",  # 余弦 0.707（中等相似，应被相对阈值剔除）
            ]),
        ])
        with patch("services.embedding.embedding_service.embed_text", _embed_text):
            with patch.object(
                __import__("core.config", fromlist=["settings"]).settings,
                "retrieval_relative_ratio",
                0.80,
            ):
                results = self._run(
                    rag_service.retrieve("后端刚入职要干嘛", ["kb1"], "t1", top_n=5)
                )
        sources = [r.source for r in results]
        self.assertTrue(all("后端" in s for s in sources), f"应全为后端文档，实际: {sources}")


    def test_llm_rerank_corrects_weak_vector_ordering(self):
        """模拟真实弱向量：query 嵌入把前端块余弦分评得比后端高（串味），但 LLM 重排按语义
        判别「后端规范」应匹配后端内容，剔除前端块、保留后端 / JavaWeb（java 归入后端）。"""
        self._seed([
            ("d_back", "kb1", "t1", "后端新员工入职指南.docx", [
                "后端开发需要掌握Java",
                "后端服务部署与监控",
            ]),
            ("d_front", "kb1", "t1", "前端开发新员工入职指南.docx", [
                "前端需要掌握React",
            ]),
            ("d_java", "kb1", "t1", "JavaWeb笔记.docx", [
                "JavaWeb是后端框架",
            ]),
        ])
        # query 向量刻意贴近前端方向（弱判别器场景），使向量融合把前端排在后端之前
        async def weak_embed(text, cfg=None, client=None):
            return [0.5, 0.5, 0.0, 0.0]

        # LLM 按内容判别：含「前端」的片段给低分，其余给高分（与向量错误排序无关）
        async def fake_complete(messages, model=None, cfg=None, client=None):
            user = messages[-1]["content"]
            by_idx: dict[int, float] = {}
            for line in user.split("\n"):
                m = __import__("re").match(r"\[(\d+)\]\s*(.*)", line)
                if m:
                    idx = int(m.group(1))
                    by_idx[idx] = 0.1 if "前端" in m.group(2) else 0.9
            return json.dumps([by_idx.get(i, 0.5) for i in range(len(by_idx))])

        cfg = ModelConfig(llm_api_key="test", llm_base_url="http://x", llm_model="m")
        with patch("services.embedding.embedding_service.embed_text", weak_embed), \
                patch("services.llm.llm_service.complete", fake_complete):
            results = self._run(
                rag_service.retrieve("后端规范是什么", ["kb1"], "t1", top_n=5, cfg=cfg)
            )
        sources = [r.source for r in results]
        self.assertFalse(any("前端" in s for s in sources), f"LLM重排应剔除前端文档，实际: {sources}")
        self.assertTrue(any("后端" in s for s in sources), f"应保留后端文档，实际: {sources}")
        self.assertTrue(
            any("java" in s.lower() for s in sources),
            f"JavaWeb 应保留，实际: {sources}",
        )

    def test_llm_rerank_falls_back_on_parse_error(self):
        """LLM 返回不可解析内容时安全回退（不应用重排分数、不报错），由截断逻辑兜底。"""
        self._seed([
            ("d_back", "kb1", "t1", "后端新员工入职指南.docx", ["后端开发需要掌握Java"]),
            ("d_front", "kb1", "t1", "前端开发新员工入职指南.docx", ["前端需要掌握React"]),
        ])

        async def weak_embed(text, cfg=None, client=None):
            return [0.5, 0.5, 0.0, 0.0]

        async def bad_complete(messages, model=None, cfg=None, client=None):
            return "我不是JSON"

        cfg = ModelConfig(llm_api_key="test", llm_base_url="http://x", llm_model="m")
        with patch("services.embedding.embedding_service.embed_text", weak_embed), \
                patch("services.llm.llm_service.complete", bad_complete):
            results = self._run(
                rag_service.retrieve("后端规范是什么", ["kb1"], "t1", top_n=5, cfg=cfg)
            )
        # 回退后不做重排，按向量融合顺序截断；不抛异常即视为安全
        self.assertTrue(results, "解析失败时不应崩溃，应返回截断后的候选")

    def test_rerank_pool_enlarged_before_rerank(self):
        """方案A：开启 LLM 重排时融合候选池应为 rerank_top_k(>top_n)，而非直接 top_n；
        重排后再按 top_n 截断，避免模糊问句相关块未进前 top_n 被漏召回。"""
        # 12 个块分布于 3 文档（单文档 <= max_chunks_per_doc=5），全无关以避免阈值过滤清空
        async def weak_embed(text, cfg=None, client=None):
            return [0.4, 0.4, 0.0, 0.0]

        captured: dict[str, int] = {}

        # 不真正调用 LLM：直接记录送入重排的候选数并原样返回（score 已由向量给出，均高）
        async def spy_rerank(query, results, top_n, cfg=None):
            captured["n"] = len(results)
            return results, True

        cfg = ModelConfig(llm_api_key="test", llm_base_url="http://x", llm_model="m")
        # 自定义构造：12 个块分布于 3 文档（单文档 <= max_chunks_per_doc=5），向量非零且与
        # query 同向（余弦=1.0），保证向量主导融合能召回全部，从而验证候选池扩到 rerank_top_k。
        async def _do():
            for d in range(3):
                items = [
                    {
                        "content": f"无关片段{d}{i}",
                        "metadata": {"source": f"doc{d}.docx", "chunk_index": i},
                        "embedding": [0.3, 0.3, 0.3, 0.3],
                    }
                    for i in range(4)
                ]
                await self.store.store_chunks(items, "kb1", f"d{d}", "t1")

        self._run(_do())
        with patch("services.embedding.embedding_service.embed_text", weak_embed), \
                patch.object(rag_service, "_rerank_with_llm", spy_rerank):
            results = self._run(
                rag_service.retrieve("模糊问句相关块未进前top_n", ["kb1"], "t1", top_n=5, cfg=cfg)
            )
        # 融合候选池应扩大为 rerank_top_k（默认 10），再精排取 top_n=5
        self.assertGreaterEqual(
            captured.get("n", 0), settings.retrieval_rerank_top_k,
            f"送重排候选池应>=rerank_top_k，实际: {captured}",
        )
        self.assertEqual(len(results), 5, f"最终应截断到 top_n=5，实际: {len(results)}")


if __name__ == "__main__":
    unittest.main()
