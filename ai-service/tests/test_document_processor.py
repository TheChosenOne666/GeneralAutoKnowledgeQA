"""文档处理分页提取单测（M4-4 增强：前端预览真实翻页）。

验证 document_processor.extract_pages 对四种可上传格式（pdf/docx/txt/md）
返回真实/估算分页，且拼接回全文与原文一致、page_no 从 1 递增。

注：项目未引入 pytest-asyncio，async 协程用标准 asyncio.run 驱动（见 test_llm.py）。
"""
import asyncio
import os
import tempfile

import pytest

from services.document_processor import document_processor, CHARS_PER_PAGE, PageSegment, _clean_text


def _write_tmp(suffix: str, content: str) -> str:
    # 二进制写入，忠实保留原文换行符（Windows 文本模式会把 \n 转成 \r\n，导致拼接回不一致）
    fd, path = tempfile.mkstemp(suffix=suffix)
    with os.fdopen(fd, "wb") as f:
        f.write(content.encode("utf-8"))
    return path


def test_extract_pages_txt_estimated():
    """TXT 按 CHARS_PER_PAGE 估算分页，拼接回全文一致。"""
    text = "知识库正文内容。" * 200
    path = _write_tmp(".txt", text)
    try:
        pages = asyncio.run(document_processor.extract_pages(path, "txt"))
        assert len(pages) >= 1
        assert "".join(p.text for p in pages) == text
        assert [p.page for p in pages] == list(range(1, len(pages) + 1))
        assert len(pages) == max(1, (len(text) + CHARS_PER_PAGE - 1) // CHARS_PER_PAGE)
    finally:
        os.unlink(path)


def test_extract_pages_md_estimated():
    """MD 同 TXT 走估算分页。"""
    text = "# 标题\n" + "段落内容。" * 150
    path = _write_tmp(".md", text)
    try:
        pages = asyncio.run(document_processor.extract_pages(path, "md"))
        assert "".join(p.text for p in pages) == text
        assert [p.page for p in pages] == list(range(1, len(pages) + 1))
    finally:
        os.unlink(path)


def test_extract_pages_docx_estimated():
    """DOCX 提取正文 + 表格，估算分页，page_no 递增。"""
    from docx import Document

    path = _write_tmp(".docx", "")
    try:
        doc = Document()
        doc.add_paragraph("第一段内容。" * 50)
        table = doc.add_table(rows=2, cols=2)
        table.cell(0, 0).text = "单元格A"
        table.cell(0, 1).text = "单元格B"
        doc.save(path)
        pages = asyncio.run(document_processor.extract_pages(path, "docx"))
        joined = "".join(p.text for p in pages)
        assert "第一段内容" in joined
        assert "单元格A" in joined and "单元格B" in joined
        assert [p.page for p in pages] == list(range(1, len(pages) + 1))
    finally:
        os.unlink(path)


def test_extract_pages_missing_file():
    """文件不存在应抛 FileNotFoundError，由路由捕获并降级。"""
    with pytest.raises(FileNotFoundError):
        asyncio.run(document_processor.extract_pages("/nonexistent/x.txt", "txt"))


def test_extract_pdf_falls_back_to_pdfplumber(monkeypatch):
    """PyMuPDF 不可用（如 _mupdf.pyd 因 DLL 加载失败）时降级到纯 Python 的 pdfplumber。

    通过强制 ``import fitz`` 抛 ImportError 并注入假 pdfplumber 模块，确定性验证降级分支。
    """
    import builtins
    import sys
    import types

    real_import = builtins.__import__

    def fake_import(name, *args, **kwargs):
        if name == "fitz" or name.startswith("fitz."):
            raise ImportError("No module named 'mupdf'")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", fake_import)

    class _FakePage:
        def extract_text(self):
            return "降级提取文本"

    class _FakePdf:
        def __init__(self):
            self.pages = [_FakePage()]

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    fake_pdfplumber = types.SimpleNamespace(open=lambda _path: _FakePdf())
    monkeypatch.setitem(sys.modules, "pdfplumber", fake_pdfplumber)

    pages = asyncio.run(document_processor._extract_pdf("dummy.pdf"))
    assert pages == [PageSegment("降级提取文本", 1)]


def test_clean_text_strips_garbage():
    """_clean_text 应剔除中文 PDF 文本层常见的乱码码位，避免引用来源出现「�」。"""
    # C1 控制符（U+0085 下一行 / U+0096 等）应被剔除，保留可见文字与换行
    assert _clean_text("正文\u0085内容\n第二行") == "正文内容\n第二行"
    # Unicode 替换符 U+FFFD
    assert _clean_text("知识\uFFFD库内容") == "知识库内容"
    # 非字符码位 U+FFFE / U+FDEF
    assert _clean_text("a\ufffeb") == "ab"
    assert _clean_text("x\ufdefy") == "xy"
    # 孤立代理对（单个代理单元）应剔除，不污染后续字符
    assert _clean_text("前\ud83d后") == "前后"
    # 合法 emoji（完整代理对）应保留
    assert _clean_text("点赞\U0001f44d") == "点赞\U0001f44d"
    # BOM 与制表/换行保留
    assert _clean_text("\ufeff\t\t标题\r\n正文") == "\t\t标题\r\n正文"
    # 空输入
    assert _clean_text("") == ""

