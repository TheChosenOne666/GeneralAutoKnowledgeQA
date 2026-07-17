"""状态/阶段回调单测（M5-4 阶段化 span 时间线追踪）。

验证：
- notify_stage 向 /api/internal/document/stage 投递正确的阶段事件体（含可选计时/指标字段）；
- 回调失败仅告警、不抛出（best-effort，不影响主流程）。
"""

import asyncio
from unittest.mock import AsyncMock

import pytest

from services import status_callback


def _run(coro):
    return asyncio.run(coro)


def test_notify_stage_posts_correct_body():
    """notify_stage 应把阶段事件（含可选计时/指标）POST 到内部 /stage 接口。"""
    client = AsyncMock()
    client.post = AsyncMock()
    resp = AsyncMock()
    resp.raise_for_status = AsyncMock()
    client.post.return_value = resp

    _run(
        status_callback.notify_stage(
            "doc9",
            "embedding",
            "done",
            started_at=1000,
            ended_at=1500,
            elapsed_ms=500,
            metrics={"chunkCount": 7, "vectorsWritten": 7},
            client=client,
        )
    )

    assert client.post.called
    args, kwargs = client.post.call_args
    assert args[0].endswith("/api/internal/document/stage")
    body = kwargs["json"]
    assert body["docId"] == "doc9"
    assert body["stage"] == "embedding"
    assert body["status"] == "done"
    assert body["startedAt"] == 1000
    assert body["endedAt"] == 1500
    assert body["elapsedMs"] == 500
    assert body["metrics"] == {"chunkCount": 7, "vectorsWritten": 7}


def test_notify_stage_omits_none_fields():
    """未提供的可选字段不应出现在请求体中。"""
    client = AsyncMock()
    client.post = AsyncMock()
    resp = AsyncMock()
    resp.raise_for_status = AsyncMock()
    client.post.return_value = resp

    _run(status_callback.notify_stage("docA", "parsing", "active", client=client))

    body = client.post.call_args.kwargs["json"]
    assert set(body.keys()) == {"docId", "stage", "status"}
    assert body["docId"] == "docA"
    assert body["stage"] == "parsing"
    assert body["status"] == "active"


def test_notify_stage_failure_is_best_effort():
    """回调异常仅告警、不抛出，不影响主流程。"""
    client = AsyncMock()
    client.post = AsyncMock(side_effect=RuntimeError("network down"))

    # 不应抛异常
    _run(status_callback.notify_stage("docB", "indexing", "done", client=client))
