"""状态回调 — Python 在处理阶段边界回调 Java 内部接口更新文档状态。

M5-1 起，Java 不再依赖 ``/document/process`` 的同步返回落库状态/全文，而是完全由本回调
驱动（retrieving/optimizing/ready/failed）。因此回调除状态外还可携带全文 content 与
分块数 chunk_count（optimizing 阶段回填全文，供前端「查看内容」弹窗）。

best-effort：回调失败仅告警，不阻塞文档处理主流程。
"""

import httpx
from loguru import logger

from core.config import settings


async def notify_document_status(
    doc_id: str,
    status: str,
    client: httpx.AsyncClient | None = None,
    content: str | None = None,
    chunk_count: int | None = None,
    error_msg: str | None = None,
    model_config_error: bool | None = None,
) -> None:
    """回调 Java 内部接口更新文档状态。

    Args:
        doc_id: 文档 ID
        status: 目标状态（processing/parsing/retrieving/optimizing/ready/failed）
        client: 可选 httpx 客户端（测试注入 mock）
        content: 可选文档全文（M5-1：optimizing 阶段经回调回填，替代旧同步返回）
        chunk_count: 可选分块数
        error_msg: 可选错误信息（failed 阶段回填，供前端展示）
        model_config_error: 可选，是否因模型配置错误导致失败（引导用户重配）
    """
    url = f"{settings.backend_base_url}/api/internal/document/status"
    body: dict = {"docId": doc_id, "status": status}
    if content is not None:
        body["content"] = content
    if chunk_count is not None:
        body["chunkCount"] = chunk_count
    if error_msg is not None:
        body["errorMsg"] = error_msg
    if model_config_error is not None:
        body["modelConfigError"] = model_config_error
    try:
        if client is None:
            async with httpx.AsyncClient(timeout=30.0) as c:
                resp = await c.post(url, json=body)
                resp.raise_for_status()
        else:
            resp = await client.post(url, json=body)
            resp.raise_for_status()
        logger.info(f"[状态回调] 已通知 doc_id={doc_id} status={status}"
                    f"{' (含全文)' if content is not None else ''}")
    except Exception as e:
        logger.warning(f"[状态回调] 更新状态失败（不影响主流程）doc_id={doc_id} status={status}: {e}")


async def notify_stage(
    doc_id: str,
    stage: str,
    status: str,
    *,
    started_at: int | None = None,
    ended_at: int | None = None,
    elapsed_ms: int | None = None,
    error: str | None = None,
    metrics: dict | None = None,
    client: httpx.AsyncClient | None = None,
) -> None:
    """回调 Java 内部接口记录文档处理阶段（M5-4 阶段化 span 时间线追踪）。

    对齐 业界 ``beginStage/endStage/failStage``：把 解析(parsing)/分块(chunking)/
    向量化(embedding)/入库(indexing)/增强(optimizing) 拆成带时间线与指标的阶段，
    供前端细粒度展示进度与失败定位。

    Args:
        doc_id: 文档 ID
        stage: 阶段名（parsing/chunking/embedding/indexing/optimizing）
        status: 阶段状态（active/done/failed）
        started_at: 阶段开始时间（epoch 毫秒，可选）
        ended_at: 阶段结束时间（epoch 毫秒，可选）
        elapsed_ms: 阶段耗时（毫秒，可选）
        error: 失败原因（status=failed 时回填，可选）
        metrics: 阶段指标（如 vectors_written / chunk_count / elapsed_ms，可选）
        client: 可选 httpx 客户端（测试注入 mock）
    """
    url = f"{settings.backend_base_url}/api/internal/document/stage"
    body: dict = {"docId": doc_id, "stage": stage, "status": status}
    if started_at is not None:
        body["startedAt"] = started_at
    if ended_at is not None:
        body["endedAt"] = ended_at
    if elapsed_ms is not None:
        body["elapsedMs"] = elapsed_ms
    if error is not None:
        body["error"] = error
    if metrics is not None:
        body["metrics"] = metrics
    try:
        if client is None:
            async with httpx.AsyncClient(timeout=30.0) as c:
                resp = await c.post(url, json=body)
                resp.raise_for_status()
        else:
            resp = await client.post(url, json=body)
            resp.raise_for_status()
        logger.info(f"[阶段回调] 已记录 doc_id={doc_id} stage={stage} status={status}")
    except Exception as e:
        logger.warning(f"[阶段回调] 记录阶段失败（不影响主流程）doc_id={doc_id} stage={stage}: {e}")
