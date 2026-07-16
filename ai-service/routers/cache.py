"""缓存管理路由 — 文档变更时主动失效 L1 检索结果缓存（对标业界成熟方案 文档库更新刷新策略）。

失效范围是 tenant 级：文档上传 / 删除后，该租户下所有检索结果缓存（retrieval:{tenant}:*）
已失效，下次提问回源走完整 RAG 检索并重新写入。
"""

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

from core.redis_client import get_redis

router = APIRouter()


class InvalidateRequest(BaseModel):
    """缓存失效请求。"""

    tenant_id: str = ""
    scope: str = "retrieval"  # retrieval（默认） / embedding / all


@router.post("/cache/invalidate")
async def invalidate(body: InvalidateRequest):
    """主动失效缓存。

    文档变更时由 Java 后端调用，清掉可能指向旧文档的检索结果（retrieval:{tenant}:*）。
    embedding 缓存以文本哈希为 key，文档内容变更自然不命中，故默认不清。
    """
    r = await get_redis()
    deleted = 0

    if body.scope in ("retrieval", "all"):
        async for key in r.scan_iter(match=f"retrieval:{body.tenant_id}:*"):
            await r.delete(key)
            deleted += 1

    if body.scope in ("embedding", "all"):
        async for key in r.scan_iter(match="embedding:*"):
            await r.delete(key)
            deleted += 1

    logger.info(f"缓存失效 tenant={body.tenant_id} scope={body.scope} deleted={deleted}")
    return {"deleted": deleted}
