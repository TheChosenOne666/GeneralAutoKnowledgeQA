"""管理路由 — M5-8 ES 存量迁移等运维操作。"""

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

from core.config import settings
from services.elasticsearch_store import get_es_store

router = APIRouter()


class ReindexResponse(BaseModel):
    """ES 存量迁移响应。"""

    status: str
    migrated_chunks: int = 0
    detail: str = ""


@router.post("/admin/reindex-es")
async def reindex_es() -> ReindexResponse:
    """M5-8 存量迁移：从 PG 全量重建所有租户的 ES 索引。仅 ``elasticsearch_enabled=True`` 时可用。"""
    if not settings.elasticsearch_enabled:
        return ReindexResponse(status="skipped", detail="elasticsearch_enabled=False")
    es = get_es_store()
    if es is None:
        return ReindexResponse(status="skipped", detail="ES 未初始化")
    try:
        n = await es.reindex_from_pg()
        return ReindexResponse(status="ok", migrated_chunks=n)
    except Exception as e:
        logger.error(f"[ES] 存量迁移失败: {e}")
        return ReindexResponse(status="failed", detail=str(e))
