"""问答增强持久化任务队列（对齐 业界 finalizing（异步增强） 任务队列 + sweep）。

设计要点：
- 任务持久化在 Redis list（``xiongda:augment:queue``），服务重启不丢；
- 采用 queue → processing 两段式（RPOPLPUSH），processing 中的任务携带 ``started_at``，
  启动时 ``sweep_stale`` 把卡死（处理中超时）的任务移回 queue，实现崩溃恢复；
- 取消集（``xiongda:augment:cancelled``，set）记录已删除文档的 doc_id，
  worker 取任务前检查，命中则跳过（文档已删无需增强，对标业界成熟方案 任务取消）。
"""

import json
import time

from core.redis_client import get_redis

QUEUE_KEY = "xiongda:augment:queue"
PROCESSING_KEY = "xiongda:augment:processing"
CANCELLED_KEY = "xiongda:augment:cancelled"


def _dump(task: dict) -> str:
    return json.dumps(task, ensure_ascii=False)


async def enqueue(task: dict) -> None:
    """将增强任务入队（持久化）。

    task 至少含 doc_id / kb_id / tenant_id / file_path / file_type / ai_config。
    """
    r = await get_redis()
    t = dict(task)
    t.setdefault("started_at", None)
    await r.rpush(QUEUE_KEY, _dump(t))


async def dequeue() -> dict | None:
    """原子取出一个任务并移入 processing（带 started_at）。空队列返回 None。"""
    r = await get_redis()
    raw = await r.rpoplpush(QUEUE_KEY, PROCESSING_KEY)
    if raw is None:
        return None
    t = json.loads(raw)
    t["started_at"] = time.time()
    new_raw = _dump(t)
    # 用带 started_at 的新记录替换 processing 中的旧记录
    await r.lrem(PROCESSING_KEY, 1, raw)
    await r.rpush(PROCESSING_KEY, new_raw)
    return t


async def ack(task: dict) -> None:
    """任务完成 / 失败后从 processing 移除。"""
    r = await get_redis()
    await r.lrem(PROCESSING_KEY, 1, _dump(task))


async def mark_cancelled(doc_id: str) -> None:
    """标记文档增强任务已取消（文档被删除时调用）。"""
    r = await get_redis()
    await r.sadd(CANCELLED_KEY, doc_id)


async def is_cancelled(doc_id: str) -> bool:
    """文档增强任务是否已取消。"""
    r = await get_redis()
    return bool(await r.sismember(CANCELLED_KEY, doc_id))


async def sweep_stale(max_age: float = 600.0) -> int:
    """把 processing 中 started_at 超时的任务移回 queue（崩溃恢复）。返回移回数量。"""
    r = await get_redis()
    items = await r.lrange(PROCESSING_KEY, 0, -1)
    now = time.time()
    moved = 0
    for raw in items:
        t = json.loads(raw)
        if now - (t.get("started_at") or 0) > max_age:
            await r.lrem(PROCESSING_KEY, 1, raw)
            t2 = dict(t)
            t2["started_at"] = None
            await r.rpush(QUEUE_KEY, _dump(t2))
            moved += 1
    return moved
