"""状态回调 — Python 在处理阶段边界回调 Java 内部接口更新文档状态。

best-effort：回调失败仅告警，不阻塞文档处理主流程（最终 ready/failed 仍由
Java 依据 Python 同步返回结果落库，此处仅用于实时透出中间阶段）。
"""

import httpx
from loguru import logger

from core.config import settings


async def notify_document_status(
    doc_id: str, status: str, client: httpx.AsyncClient | None = None
) -> None:
    """回调 Java 内部接口更新文档状态。

    Args:
        doc_id: 文档 ID
        status: 目标状态（processing/parsing/retrieving/optimizing/ready/failed）
        client: 可选 httpx 客户端（测试注入 mock）
    """
    url = f"{settings.backend_base_url}/api/internal/document/status"
    body = {"docId": doc_id, "status": status}
    try:
        if client is None:
            async with httpx.AsyncClient(timeout=10.0) as c:
                resp = await c.post(url, json=body)
                resp.raise_for_status()
        else:
            resp = await client.post(url, json=body)
            resp.raise_for_status()
        logger.info(f"[状态回调] 已通知 doc_id={doc_id} status={status}")
    except Exception as e:
        logger.warning(f"[状态回调] 更新状态失败（不影响主流程）doc_id={doc_id} status={status}: {e}")
