"""文档处理路由 — 接收 Java 后端的文档处理请求。"""

from fastapi import APIRouter
from pydantic import BaseModel

from services.document_processor import document_processor

router = APIRouter()


class ProcessRequest(BaseModel):
    """文档处理请求（Java 后端调用）。"""

    doc_id: str
    file_path: str
    file_type: str  # pdf / docx / md / txt
    kb_id: str
    tenant_id: str


@router.post("/document/process")
async def process_document(body: ProcessRequest):
    """文档处理流水线：提取 → 分块 → 向量化 → 存储。

    Java 后端上传文件后调用此接口触发异步处理。
    """
    try:
        chunk_count = await document_processor.process(
            file_path=body.file_path,
            file_type=body.file_type,
            kb_id=body.kb_id,
            doc_id=body.doc_id,
            tenant_id=body.tenant_id,
        )
        return {
            "doc_id": body.doc_id,
            "status": "ready",
            "chunk_count": chunk_count,
        }
    except Exception as e:
        return {
            "doc_id": body.doc_id,
            "status": "failed",
            "error": str(e),
        }
