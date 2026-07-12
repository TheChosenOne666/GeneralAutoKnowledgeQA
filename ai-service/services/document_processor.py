"""文档处理服务 — 提取 → 分块 → 向量化 → 存储。

使用 LangChain 的文档加载器和文本分割器。
M2-3：完整链路已接入 Embedding 与 VectorStore（M1-5 阶段仅提取+分块）。
"""

import os
from dataclasses import dataclass

from loguru import logger

from core.config import settings
from services import vector_store as vector_store_module
from services.embedding import embedding_service
from services.model_config import ModelConfig


@dataclass
class DocumentChunk:
    """文档分块。"""

    content: str
    metadata: dict
    embedding: list[float] | None = None


class DocumentProcessor:
    """文档处理流水线。"""

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
    ) -> int:
        """完整文档处理流水线：提取 → 分块 → 向量化 → 存储。

        Returns:
            分块数量
        """
        # 1. 提取文本
        logger.info(f"[文档诊断] 开始提取文本 doc_id={doc_id} file_type={file_type} file_path={file_path}")
        text = await self.extract_text(file_path, file_type)
        logger.info(f"[文档诊断] 提取完成 doc_id={doc_id} 文本长度={len(text)}")

        # 2. 分块
        chunks = await self.chunk_text(text)
        logger.info(f"[文档诊断] 分块完成 doc_id={doc_id} 块数={len(chunks)}")

        # 3. 向量化
        logger.info(f"[文档诊断] 开始向量化 doc_id={doc_id} 块数={len(chunks)}")
        chunks = await self.embed_chunks(chunks, cfg)
        logger.info(f"[文档诊断] 向量化完成 doc_id={doc_id}")

        # 4. 存储（含元数据：doc_id, kb_id, tenant_id, source, page）
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
                    "page": 0,
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

        return len(chunks)


document_processor = DocumentProcessor()
