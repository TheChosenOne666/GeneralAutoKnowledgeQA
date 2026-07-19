"""M6-4 相邻块补全 单元测试。"""

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

from services.vector_store import InMemoryVectorStore, RetrievalResult, _ChunkRecord


def _make_result(
    doc_id: str = "doc1",
    kb_id: str = "kb1",
    chunk_index: int = 0,
    content: str = "内容",
    chunk_type: str = "text",
) -> RetrievalResult:
    return RetrievalResult(
        content=content,
        source="test.pdf",
        page=1,
        score=0.9,
        doc_id=doc_id,
        kb_id=kb_id,
        chunk_index=chunk_index,
        chunk_type=chunk_type,
    )


def _make_record(
    doc_id: str = "doc1",
    kb_id: str = "kb1",
    chunk_index: int = 0,
    content: str = "内容",
    chunk_type: str = "text",
    tenant_id: str = "t1",
) -> _ChunkRecord:
    return _ChunkRecord(
        content=content,
        embedding=[0.1, 0.2],
        metadata={
            "doc_id": doc_id,
            "kb_id": kb_id,
            "chunk_index": chunk_index,
            "chunk_type": chunk_type,
            "source": "test.pdf",
            "page": 1,
            "tenant_id": tenant_id,
        },
    )


class TestAdjacentChunkExpansion:
    """M6-4：相邻块补全。"""

    def test_single_hit_with_adjacent(self):
        """单个命中块，前后各补全一个块。"""
        store = InMemoryVectorStore()
        store._records = [
            _make_record(doc_id="doc1", chunk_index=0, content="块0"),
            _make_record(doc_id="doc1", chunk_index=1, content="块1"),
            _make_record(doc_id="doc1", chunk_index=2, content="块2"),
        ]
        results = [_make_result(doc_id="doc1", chunk_index=1, content="块1")]
        adjacent = asyncio.run(store.get_adjacent_chunks(results))
        assert len(adjacent) == 2
        indices = sorted(a.chunk_index for a in adjacent)
        assert indices == [0, 2]
        assert all(a.is_context_expansion for a in adjacent)

    def test_no_adjacent_chunks(self):
        """命中块在文档边缘（chunk_index=0），只有后一块。"""
        store = InMemoryVectorStore()
        store._records = [
            _make_record(doc_id="doc1", chunk_index=0, content="块0"),
            _make_record(doc_id="doc1", chunk_index=1, content="块1"),
        ]
        results = [_make_result(doc_id="doc1", chunk_index=0, content="块0")]
        adjacent = asyncio.run(store.get_adjacent_chunks(results))
        assert len(adjacent) == 1
        assert adjacent[0].chunk_index == 1
        assert adjacent[0].is_context_expansion

    def test_already_in_results_not_duplicated(self):
        """已在 results 中的块不重复补全。"""
        store = InMemoryVectorStore()
        store._records = [
            _make_record(doc_id="doc1", chunk_index=0, content="块0"),
            _make_record(doc_id="doc1", chunk_index=1, content="块1"),
            _make_record(doc_id="doc1", chunk_index=2, content="块2"),
        ]
        results = [
            _make_result(doc_id="doc1", chunk_index=0, content="块0"),
            _make_result(doc_id="doc1", chunk_index=2, content="块2"),
        ]
        adjacent = asyncio.run(store.get_adjacent_chunks(results))
        assert len(adjacent) == 1
        assert adjacent[0].chunk_index == 1

    def test_exclude_augment_chunks(self):
        """相邻块是增强块（qa/summary 等）时不补全。"""
        store = InMemoryVectorStore()
        store._records = [
            _make_record(doc_id="doc1", chunk_index=0, content="原文块0"),
            _make_record(doc_id="doc1", chunk_index=1, content="原文块1"),
            _make_record(doc_id="doc1", chunk_index=2, content="QA增强块", chunk_type="qa"),
        ]
        results = [_make_result(doc_id="doc1", chunk_index=1, content="块1")]
        adjacent = asyncio.run(store.get_adjacent_chunks(results))
        assert len(adjacent) == 1
        assert adjacent[0].chunk_index == 0

    def test_exclude_parent_chunks(self):
        """相邻块是 parent 父块时不补全。"""
        store = InMemoryVectorStore()
        store._records = [
            _make_record(doc_id="doc1", chunk_index=0, content="parent块", chunk_type="parent"),
            _make_record(doc_id="doc1", chunk_index=1, content="子块1"),
            _make_record(doc_id="doc1", chunk_index=2, content="子块2"),
        ]
        results = [_make_result(doc_id="doc1", chunk_index=1, content="块1")]
        adjacent = asyncio.run(store.get_adjacent_chunks(results))
        assert len(adjacent) == 1
        assert adjacent[0].chunk_index == 2

    def test_multiple_documents(self):
        """多个文档的命中块各自补全相邻块。"""
        store = InMemoryVectorStore()
        store._records = [
            _make_record(doc_id="doc1", chunk_index=0, content="d1块0"),
            _make_record(doc_id="doc1", chunk_index=1, content="d1块1"),
            _make_record(doc_id="doc2", chunk_index=0, content="d2块0"),
            _make_record(doc_id="doc2", chunk_index=1, content="d2块1"),
        ]
        results = [
            _make_result(doc_id="doc1", chunk_index=1, content="d1块1"),
            _make_result(doc_id="doc2", chunk_index=0, content="d2块0"),
        ]
        adjacent = asyncio.run(store.get_adjacent_chunks(results))
        assert len(adjacent) == 2
        docs = {a.doc_id: a.chunk_index for a in adjacent}
        assert docs == {"doc1": 0, "doc2": 1}

    def test_empty_results(self):
        """空结果列表。"""
        store = InMemoryVectorStore()
        adjacent = asyncio.run(store.get_adjacent_chunks([]))
        assert len(adjacent) == 0


if __name__ == "__main__":
    test = TestAdjacentChunkExpansion()
    test.test_single_hit_with_adjacent()
    test.test_no_adjacent_chunks()
    test.test_already_in_results_not_duplicated()
    test.test_exclude_augment_chunks()
    test.test_exclude_parent_chunks()
    test.test_multiple_documents()
    test.test_empty_results()
    print("M6-4 单测全部通过 (7/7)")
