"""document_processor 单元测试 — 覆盖页码分配与乱码清洗（对应问题1/问题2）。"""

import asyncio
import os
import tempfile

from services.document_processor import (
    CHARS_PER_PAGE,
    DocumentProcessor,
    PageSegment,
    _clean_text,
)


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


def test_clean_text_removes_replacement_and_control_chars():
    dirty = "正常中文\u0000\u0007结尾\ufffd乱码"
    cleaned = _clean_text(dirty)
    assert "\ufffd" not in cleaned
    assert "\x00" not in cleaned
    assert "\x07" not in cleaned
    assert "正常中文" in cleaned and "结尾" in cleaned and "乱码" in cleaned


def test_clean_text_strips_bom():
    assert _clean_text("\ufeff标题") == "标题"


def test_estimate_pages_increments():
    long_text = "内容" * (CHARS_PER_PAGE + 10)
    segs = DocumentProcessor._estimate_pages(long_text)
    assert len(segs) >= 2
    assert [s.page for s in segs] == list(range(1, len(segs) + 1))
    # 每段页码唯一且递增
    assert segs[0].page == 1 and segs[1].page == 2


def test_extract_pages_txt_with_encoding_fallback():
    with tempfile.NamedTemporaryFile(
        "wb", suffix=".txt", delete=False
    ) as f:
        # 以 gbk 写入中文，验证编码回退不出现乱码
        f.write("运维工程师入职指南：安全规范与操作流程".encode("gbk"))
        path = f.name
    try:
        segs = _run(DocumentProcessor().extract_pages(path, "txt"))
        full = "\n".join(s.text for s in segs)
        assert "运维工程师入职指南" in full
        assert "\ufffd" not in full
        assert all(s.page >= 1 for s in segs)
    finally:
        os.unlink(path)


def test_extract_pages_docx_includes_tables_and_pages():
    from docx import Document as DocxDocument

    with tempfile.NamedTemporaryFile(suffix=".docx", delete=False) as f:
        path = f.name
    try:
        doc = DocxDocument()
        doc.add_paragraph("第一章 入职须知")
        table = doc.add_table(rows=2, cols=2)
        table.cell(0, 0).text = "项目"
        table.cell(0, 1).text = "要求"
        table.cell(1, 0).text = "培训"
        table.cell(1, 1).text = "完成入职培训"
        # 加入一些乱码占位字符验证清洗
        doc.add_paragraph("注意事项\ufffd示例")
        doc.save(path)

        segs = _run(DocumentProcessor().extract_pages(path, "docx"))
        full = "\n".join(s.text for s in segs)
        assert "第一章 入职须知" in full
        assert "项目" in full and "完成入职培训" in full
        assert "\ufffd" not in full
        assert all(s.page >= 1 for s in segs)
    finally:
        os.unlink(path)


def test_chunk_text_preserves_page_metadata():
    pages = [
        PageSegment("第一段内容关于安全规范。", 1),
        PageSegment("第二段内容关于操作流程。", 3),
    ]
    chunks = _run(DocumentProcessor().chunk_text(pages))
    assert len(chunks) >= 1
    # 每块都带有页码元数据，且页码来自对应段
    for c in chunks:
        assert "page" in c.metadata
        assert c.metadata["page"] in (1, 3)
    # chunk_index 全局唯一递增
    indices = [c.metadata["chunk_index"] for c in chunks]
    assert indices == sorted(indices)


if __name__ == "__main__":
    import unittest

    unittest.main()
