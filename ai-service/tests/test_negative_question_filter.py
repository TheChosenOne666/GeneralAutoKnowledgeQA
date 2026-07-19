"""M6-3 FAQ 负向问题过滤 单元测试。"""

import sys
import os
import asyncio
from unittest.mock import MagicMock

# 添加项目根目录到 Python 路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# Mock heavy dependencies before any project imports
_mock_asyncpg = MagicMock()
sys.modules.setdefault("asyncpg", _mock_asyncpg)
_mock_redis = MagicMock()
_mock_redis.asyncio = MagicMock()
sys.modules.setdefault("redis", _mock_redis)
sys.modules.setdefault("redis.asyncio", _mock_redis.asyncio)

from services.rag import RagService
from services.vector_store import RetrievalResult


def _make_result(
    content: str = "测试内容",
    chunk_type: str = "qa",
    negative_questions: list[str] | None = None,
    doc_id: str = "doc1",
    chunk_index: int = 0,
) -> RetrievalResult:
    return RetrievalResult(
        content=content,
        source="test.pdf",
        page=1,
        score=0.9,
        doc_id=doc_id,
        kb_id="kb1",
        chunk_index=chunk_index,
        chunk_type=chunk_type,
        negative_questions=negative_questions or [],
    )


class TestNegativeQuestionFilter:
    """M6-3：FAQ 负向问题过滤。"""

    def test_no_negative_questions(self):
        """块没有负向问题时不过滤。"""
        results = [
            _make_result(content="Spring Boot 自动配置原理", negative_questions=[]),
            _make_result(content="MyBatis 映射文件", negative_questions=[]),
        ]
        filtered = RagService._filter_negative_questions("什么是Spring Boot", results)
        assert len(filtered) == 2

    def test_query_matches_negative_question(self):
        """query 精确匹配负向问题 → 该块被剔除。"""
        results = [
            _make_result(
                content="Spring Boot 自动配置原理",
                negative_questions=["什么是MyBatis", "如何配置MyBatis"],
            ),
            _make_result(
                content="MyBatis 映射文件配置",
                negative_questions=["什么是Spring Boot"],
            ),
        ]
        filtered = RagService._filter_negative_questions("什么是Spring Boot", results)
        assert len(filtered) == 1
        assert filtered[0].content == "Spring Boot 自动配置原理"

    def test_query_not_matching_negative_question(self):
        """query 不匹配任何负向问题 → 全部保留。"""
        results = [
            _make_result(
                content="Spring Boot 自动配置原理",
                negative_questions=["什么是MyBatis", "如何配置MyBatis"],
            ),
        ]
        filtered = RagService._filter_negative_questions("Spring Boot 怎么用", results)
        assert len(filtered) == 1

    def test_case_insensitive_match(self):
        """大小写不敏感匹配。"""
        results = [
            _make_result(
                content="Docker 容器化部署",
                negative_questions=["What is Kubernetes"],
            ),
        ]
        filtered = RagService._filter_negative_questions("what is kubernetes", results)
        assert len(filtered) == 0

    def test_non_qa_chunk_not_filtered(self):
        """非 QA 类型块即使有负向问题也不过滤。"""
        results = [
            _make_result(
                content="原始文档块",
                chunk_type="text",
                negative_questions=["什么是Spring Boot"],
            ),
        ]
        filtered = RagService._filter_negative_questions("什么是Spring Boot", results)
        assert len(filtered) == 1

    def test_empty_query(self):
        """空 query 不触发过滤。"""
        results = [
            _make_result(content="测试", negative_questions=["什么测试"]),
        ]
        filtered = RagService._filter_negative_questions("", results)
        assert len(filtered) == 1

    def test_empty_results(self):
        """空结果列表。"""
        filtered = RagService._filter_negative_questions("任意问题", [])
        assert len(filtered) == 0

    def test_multiple_blocks_some_matched(self):
        """多个块部分命中负向问题。"""
        results = [
            _make_result(
                content="块1",
                doc_id="doc1",
                chunk_index=0,
                negative_questions=["错误问题A"],
            ),
            _make_result(
                content="块2",
                doc_id="doc2",
                chunk_index=1,
                negative_questions=["错误问题B"],
            ),
            _make_result(
                content="块3",
                doc_id="doc3",
                chunk_index=2,
                negative_questions=["错误问题C"],
            ),
        ]
        filtered = RagService._filter_negative_questions("错误问题B", results)
        assert len(filtered) == 2
        assert filtered[0].content == "块1"
        assert filtered[1].content == "块3"


if __name__ == "__main__":
    test = TestNegativeQuestionFilter()
    test.test_no_negative_questions()
    test.test_query_matches_negative_question()
    test.test_query_not_matching_negative_question()
    test.test_case_insensitive_match()
    test.test_non_qa_chunk_not_filtered()
    test.test_empty_query()
    test.test_empty_results()
    test.test_multiple_blocks_some_matched()
    print("M6-3 单测全部通过 (8/8)")
