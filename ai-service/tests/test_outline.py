"""方案C（大纲感知分块 + 大纲专用召回）与预览兜底（向量库重建分页）单测。

验证点：
- 标题行识别（编号 / 章节词 / 架构词 / 追问短句）
- 大纲意图关键词命中
- 向量检索 chunk_types 过滤（仅返回 outline 块）
- 向量库按页重建分页（预览兜底，排除 qa 块、按 page 聚合）
"""

import asyncio

from services.document_processor import document_processor
from services.rag import RagService
from services.vector_store import InMemoryVectorStore, _ChunkRecord


def test_is_title_line_various():
    """各类标题行应被识别，长正文 / 追问句 / 普通短句不应。"""
    assert document_processor._is_title_line("1. 引言")
    assert document_processor._is_title_line("2.2 Java并发编程")
    assert document_processor._is_title_line("(1) 线程安全")
    assert document_processor._is_title_line("第三章 集合框架")
    assert document_processor._is_title_line("知识架构总览")
    # 追问句不识别为标题（避免 outline 块过多）
    assert not document_processor._is_title_line("为什么HashMap用红黑树？")
    # 冒号短句不识别
    assert not document_processor._is_title_line("Java并发编程：")
    # 长正文不应识别为标题
    long_text = "这是一段很长的正文内容，" * 10
    assert not document_processor._is_title_line(long_text)
    # 普通短句不识别
    assert not document_processor._is_title_line("这里的代码有什么问题")
    assert not document_processor._is_title_line("你好世界")


def test_extract_outline_titles():
    """从文本中提取章节标题，排除长正文与无特征短句（追问句/冒号短句不算标题）。"""
    text = (
        "1. 引言\n"
        "这是正文段落内容很长很长很长很长很长很长很长很长很长很长。\n"
        "2.2 Java并发编程\n"
        "为什么HashMap用红黑树而不用B树？\n"
        "第三章 集合框架\n"
        "普通的一句话没有编号也不是问句。\n"
    )
    titles = document_processor._extract_outline_titles(text)
    # 编号章节 + 第X章 识别为标题
    assert "1. 引言" in titles
    assert "2.2 Java并发编程" in titles
    assert "第三章 集合框架" in titles
    # 追问句不识别为标题（避免 outline 块过多稀释常规检索）
    assert "为什么HashMap用红黑树而不用B树？" not in titles
    assert not any("这是正文段落" in t for t in titles)
    assert not any("普通的一句话" in t for t in titles)


def test_is_outline_query():
    """大纲意图问题命中，普通细节问题不命中。"""
    assert RagService._is_outline_query("Java校招重点面试知识架构是什么")
    assert RagService._is_outline_query("这份文档的目录有哪些")
    assert RagService._is_outline_query("MySQL 和 Redis 的考点有哪些")
    assert not RagService._is_outline_query("synchronized 底层原理是什么")
    assert not RagService._is_outline_query("如何处理高并发场景")


def test_inmemory_search_chunk_types_filter():
    """向量检索按 chunk_types 过滤，仅返回 outline 块（且受租户隔离）。"""
    store = InMemoryVectorStore()
    store._records = [
        _ChunkRecord(
            "普通内容块关于并发原理", [1.0, 0.0],
            {"tenant_id": "t1", "kb_id": "k1", "doc_id": "d1",
             "chunk_type": "", "page": 1, "chunk_index": 0},
        ),
        _ChunkRecord(
            "2.1 Java并发编程", [0.9, 0.1],
            {"tenant_id": "t1", "kb_id": "k1", "doc_id": "d1",
             "chunk_type": "outline", "page": 1, "chunk_index": 1},
        ),
        _ChunkRecord(
            "其他租户块", [1.0, 0.0],
            {"tenant_id": "t2", "kb_id": "k1", "doc_id": "d2",
             "chunk_type": "outline", "page": 1, "chunk_index": 0},
        ),
    ]
    results = asyncio.run(
        store.search([1.0, 0.0], ["k1"], "t1", top_k=10, chunk_types=["outline"])
    )
    assert len(results) == 1
    assert results[0].chunk_type == "outline"
    assert results[0].doc_id == "d1"
    # 常规检索（chunk_types=None）默认排除 outline，仅返回普通块（1 条）
    all_res = asyncio.run(store.search([1.0, 0.0], ["k1"], "t1", top_k=10))
    assert len(all_res) == 1
    # 显式包含 outline + 空类型时返回全部（2 条）
    all_explicit = asyncio.run(
        store.search([1.0, 0.0], ["k1"], "t1", top_k=10, chunk_types=["", "outline"])
    )
    assert len(all_explicit) == 2


def test_inmemory_get_document_pages():
    """向量库按页重建分页：按 page 聚合、排除 qa 块、仅限指定 doc。"""
    store = InMemoryVectorStore()
    store._records = [
        _ChunkRecord(
            "第1页块A", None,
            {"tenant_id": "t1", "doc_id": "d1", "chunk_type": "outline", "page": 1, "chunk_index": 0},
        ),
        _ChunkRecord(
            "第1页块B", None,
            {"tenant_id": "t1", "doc_id": "d1", "chunk_type": "content", "page": 1, "chunk_index": 1},
        ),
        _ChunkRecord(
            "第2页块", None,
            {"tenant_id": "t1", "doc_id": "d1", "chunk_type": "outline", "page": 2, "chunk_index": 2},
        ),
        _ChunkRecord(
            "其他文档", None,
            {"tenant_id": "t1", "doc_id": "d2", "chunk_type": "outline", "page": 1, "chunk_index": 0},
        ),
        _ChunkRecord(
            "问答增强块", None,
            {"tenant_id": "t1", "doc_id": "d1", "chunk_type": "qa", "page": 1, "chunk_index": 3},
        ),
    ]
    pages = asyncio.run(store.get_document_pages("d1"))
    assert len(pages) == 2
    assert pages[0]["page_no"] == 1
    assert "第1页块A" in pages[0]["text"] and "第1页块B" in pages[0]["text"]
    assert pages[1]["page_no"] == 2
    assert "第2页块" in pages[1]["text"]
    # qa 块被排除
    assert "问答增强块" not in pages[0]["text"]
