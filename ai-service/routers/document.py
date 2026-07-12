"""文档处理路由 — 接收 Java 后端的文档处理请求。"""

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

from services.document_processor import document_processor
from services.model_config import ModelConfig, ModelConfigError

router = APIRouter()


class ProcessRequest(BaseModel):
    """文档处理请求（Java 后端调用）。"""

    doc_id: str
    file_path: str
    file_type: str  # pdf / docx / md / txt
    kb_id: str
    tenant_id: str
    ai_config: dict | None = None  # 用户 AI 模型配置（由 Java 透传）


@router.post("/document/process")
async def process_document(body: ProcessRequest):
    """文档处理流水线：提取 → 分块 → 向量化 → 存储。

    Java 后端上传文件后调用此接口触发异步处理。
    M3-3：模型配置错误（无 Key / Key 错 / 模型名错 / 维度不匹配）返回
    error_type=MODEL_CONFIG_ERROR，供前端识别并引导重新配置。
    """
    try:
        logger.info(f"[文档诊断] 收到process doc_id={body.doc_id} file_type={body.file_type} "
                    f"file_path={body.file_path} ai_config是否为空={body.ai_config is None}")
        if body.ai_config:
            logger.info(f"[文档诊断] ai_config字段={list(body.ai_config.keys())} "
                        f"embedding_model={body.ai_config.get('embedding_model')} "
                        f"has_emb_key={bool(body.ai_config.get('embedding_api_key'))}")
        else:
            logger.warning("[文档诊断] ai_config为空，将走env兜底（易触发模型配置错误）")
        cfg = ModelConfig.from_dict(body.ai_config)
        if cfg is None:
            logger.warning("[文档诊断] ModelConfig为None，使用env兜底")
        else:
            logger.info(f"[文档诊断] ModelConfig已构建 emb={cfg.embedding_provider}/{cfg.embedding_model} "
                        f"has_emb_key={cfg.has_embedding()}")
        result = await document_processor.process(
            file_path=body.file_path,
            file_type=body.file_type,
            kb_id=body.kb_id,
            doc_id=body.doc_id,
            tenant_id=body.tenant_id,
            cfg=cfg,
        )
        return {
            "doc_id": body.doc_id,
            "status": "ready",
            "chunk_count": result.get("chunk_count", 0),
            "content": result.get("content", ""),
        }
    except ModelConfigError as e:
        logger.warning(f"模型配置错误（文档处理）：{e}")
        return {
            "doc_id": body.doc_id,
            "status": "failed",
            "error_type": "MODEL_CONFIG_ERROR",
            "error": str(e),
        }
    except Exception as e:
        return {
            "doc_id": body.doc_id,
            "status": "failed",
            "error": str(e),
        }
