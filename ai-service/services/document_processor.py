"""文档处理服务 — 提取 → 分块 → 向量化 → 存储。

使用 LangChain 的文档加载器和文本分割器。
M2-3：完整链路已接入 Embedding 与 VectorStore（M1-5 阶段仅提取+分块）。

页码策略：
- PDF：PyMuPDF 逐页提取，使用真实页码。
- DOCX / TXT / MD：无真实页码，按 CHARS_PER_PAGE 估算（仅用于引用来源展示）。
"""

import os
from dataclasses import dataclass

from loguru import logger

from core.config import settings
from services import vector_store as vector_store_module
from services.embedding import embedding_service
from services.model_config import ModelConfig

# DOCX/TXT/MD 无真实页码，按字符数估算每页（仅用于引用来源展示，非精确）
CHARS_PER_PAGE = 1500


@dataclass
class DocumentChunk:
    """文档分块。"""

    content: str
    metadata: dict
    embedding: list[float] | None = None


@dataclass
class PageSegment:
    """按页分段的文本。"""

    text: str
    page: int


def _clean_text(text: str) -> str:
    """清洗提取文本：去 BOM、去控制字符（保留换行/制表）、去替换符 U+FFFD（解析乱码占位）。"""
    if not text:
        return ""
    text = text.replace("\ufeff", "")
    # 去掉除 \n \r \t 之外的控制字符
    text = "".join(ch for ch in text if ch in "\n\r\t" or ord(ch) >= 32)
    # 去掉替换符（解析过程中产生的乱码占位）
    text = text.replace("\ufffd", "")
    return text


class DocumentProcessor:
    """文档处理流水线。"""

    @staticmethod
    def _read_text_file(file_path: str) -> str:
        """读取文本文件，依次尝试 utf-8 / gbk，避免中文 Windows 文件乱码。"""
        with open(file_path, "rb") as f:
            data = f.read()
        for enc in ("utf-8", "gbk", "latin-1"):
            try:
                return data.decode(enc)
            except (UnicodeDecodeError, LookupError):
                continue
        return data.decode("utf-8", errors="replace")

    @staticmethod
    def _estimate_pages(text: str) -> list[PageSegment]:
        """按 CHARS_PER_PAGE 估算页码（DOCX/TXT/MD 无真实页码）。"""
        text = text or ""
        if not text.strip():
            return [PageSegment(text, 1)]
        segs: list[PageSegment] = []
        for i in range(0, len(text), CHARS_PER_PAGE):
            segs.append(PageSegment(text[i : i + CHARS_PER_PAGE], len(segs) + 1))
        return segs

    async def extract_pages(self, file_path: str, file_type: str) -> list[PageSegment]:
        """提取文本并按页分段，返回 [(text, page_no), ...]。

        - PDF: PyMuPDF 逐页真实页码
        - DOCX: python-docx（正文 + 表格），无真实页码时按字符数估算
        - MD/TXT: 按字符数估算页码
        """
        ft = file_type.lower()
        if ft == "pdf":
            import fitz

            doc = fitz.open(file_path)
            try:
                segs = [PageSegment(_clean_text(page.get_text()), i + 1) for i, page in enumerate(doc)]
            finally:
                doc.close()
            return segs
        if ft == "docx":
            from docx import Document

            doc = Document(file_path)
            parts = [p.text for p in doc.paragraphs]
            # 表格内容也纳入提取，避免正文缺失
            for table in doc.tables:
                for row in table.rows:
                    cells = [cell.text for cell in row.cells]
                    parts.append(" | ".join(cells))
            full = _clean_text("\n".join(parts))
            return self._estimate_pages(full)
        # md / txt / 其他：直接读取（编码容错）
        raw = self._read_text_file(file_path)
        return self._estimate_pages(_clean_text(raw))

    async def chunk_text(self, pages: list[PageSegment]) -> list[DocumentChunk]:
        """文本分块 — 使用 LangChain RecursiveCharacterTextSplitter，保留每块的页码。"""
        from langchain_text_splitters import RecursiveCharacterTextSplitter

        splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
        )
        chunks: list[DocumentChunk] = []
        global_index = 0
        for seg in pages:
            if not seg.text.strip():
                continue
            for c in splitter.split_text(seg.text):
                chunks.append(
                    DocumentChunk(
                        content=c,
                        metadata={"chunk_index": global_index, "page": seg.page},
                    )
                )
                global_index += 1
        return chunks

    async def embed_chunks(
        self, chunks: list[DocumentChunk], cfg: ModelConfig | None = None
    ) -> list[DocumentChunk]:
        """向量化分块。"""
        texts = [c.content for c in chunks]
        vectors = await embedding_service.embed_batch(texts, cfg)
        for chunk, vec in zip(chunks, vectors):
            chunk.embedding = vec
        return chunks

    async def process(
        self,
        file_path: str,
        file_type: str,
        kb_id: str,
        doc_id: str,
        tenant_id: str,
        cfg: ModelConfig | None = None,
    ) -> dict:
        """完整文档处理流水线：提取 → 分块 → 向量化 → 存储。

        Returns:
            {"chunk_count": int, "content": str} — content 为提取全文，供前端查看。
        """
        # 1. 提取文本（按页）
        logger.info(f"[文档诊断] 开始提取文本 doc_id={doc_id} file_type={file_type} file_path={file_path}")
        pages = await self.extract_pages(file_path, file_type)
        full_text = "\n".join(p.text for p in pages)
        logger.info(f"[文档诊断] 提取完成 doc_id={doc_id} 文本长度={len(full_text)} 页数={len(pages)}")

        # 2. 分块（保留页码）
        chunks = await self.chunk_text(pages)
        logger.info(f"[文档诊断] 分块完成 doc_id={doc_id} 块数={len(chunks)}")

        # 3. 向量化
        logger.info(f"[文档诊断] 开始向量化 doc_id={doc_id} 块数={len(chunks)}")
        chunks = await self.embed_chunks(chunks, cfg)
        logger.info(f"[文档诊断] 向量化完成 doc_id={doc_id}")

        # 4. 存储（含元数据：doc_id, kb_id, tenant_id, source, page, chunk_index）
        source = os.path.basename(file_path)
        logger.info(f"[文档诊断] 开始存储 doc_id={doc_id} kb_id={kb_id} tenant_id={tenant_id}")
        chunk_dicts = []
        for c in chunks:
            meta = dict(c.metadata)
            meta.update(
                {
                    "doc_id": doc_id,
                    "kb_id": kb_id,
                    "tenant_id": tenant_id,
                    "source": source,
                }
            )
            chunk_dicts.append(
                {
                    "content": c.content,
                    "metadata": meta,
                    "embedding": c.embedding,
                }
            )
        await vector_store_module.vector_store_service.store_chunks(
            chunk_dicts, kb_id, doc_id, tenant_id
        )

        return {"chunk_count": len(chunks), "content": full_text}


document_processor = DocumentProcessor()
