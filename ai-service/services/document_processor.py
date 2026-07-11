"""文档处理服务 — 提取 → 分块 → 向量化 → 存储。

使用 LangChain 的文档加载器和文本分割器。
"""

from dataclasses import dataclass

from core.config import settings
from services.embedding import embedding_service
from services.vector_store import vector_store_service


@dataclass
class DocumentChunk:
    """文档分块。"""

    content: str
    metadata: dict
    embedding: list[float] | None = None


class DocumentProcessor:
    """文档处理流水线（骨架）。"""

    async def extract_text(self, file_path: str, file_type: str) -> str:
        """从文件提取文本。

        - PDF: PyMuPDF
        - DOCX: python-docx
        - MD/TXT: 直接读取
        """
        ft = file_type.lower()
        if ft == "pdf":
            import fitz

            doc = fitz.open(file_path)
            text = "\n".join(page.get_text() for page in doc)
            doc.close()
            return text
        elif ft == "docx":
            from docx import Document

            doc = Document(file_path)
            return "\n".join(p.text for p in doc.paragraphs)
        else:
            # md / txt / 其他：直接读取
            with open(file_path, "r", encoding="utf-8") as f:
                return f.read()

    async def chunk_text(self, text: str) -> list[DocumentChunk]:
        """文本分块 — 使用 LangChain RecursiveCharacterTextSplitter。"""
        from langchain_text_splitters import RecursiveCharacterTextSplitter

        splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
        )
        chunks = splitter.split_text(text)
        return [
            DocumentChunk(content=c, metadata={"chunk_index": i})
            for i, c in enumerate(chunks)
        ]

    async def embed_chunks(self, chunks: list[DocumentChunk]) -> list[DocumentChunk]:
        """向量化分块。"""
        texts = [c.content for c in chunks]
        vectors = await embedding_service.embed_batch(texts)
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
    ) -> int:
        """完整文档处理流水线。

        M1-5：提取文本 + 分块（向量化/存储在 M2 接入）。

        Returns:
            分块数量
        """
        # 1. 提取文本
        text = await self.extract_text(file_path, file_type)

        # 2. 分块
        chunks = await self.chunk_text(text)

        # 3-4. 向量化 + 存储（M2 实现，暂跳过）
        try:
            chunks = await self.embed_chunks(chunks)
            chunk_dicts = [
                {"content": c.content, "metadata": c.metadata, "embedding": c.embedding}
                for c in chunks
            ]
            await vector_store_service.store_chunks(chunk_dicts, kb_id, doc_id, tenant_id)
        except NotImplementedError:
            pass

        return len(chunks)


document_processor = DocumentProcessor()
