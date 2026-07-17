"""文档处理主流程持久化任务队列（M5-1，对齐业界 Asynq 整流程异步）。

把「提取 → 分块 → 向量化 → 存储」整段主流程从同步 HTTP handler 搬进持久化队列 worker，
HTTP 上传仅 enqueue 即返回，主流程异步执行、崩溃可恢复（对标业界成熟方案 把
``ProcessDocument`` 整体跑在 Asynq 持久化任务里）。

设计要点（复用 augment_queue 的可靠性模式）：
- 任务持久化在 Redis list（``xiongda:doc:queue``），服务重启不丢；
- 采用 queue → processing 两段式（RPOPLPUSH），processing 中的任务携带 ``started_at``，
  启动时 ``sweep_stale`` 把卡死（处理中超时）的任务移回 queue，实现崩溃恢复；
- 取消集（``xiongda:doc:cancelled``，set）记录已取消/删除文档的 doc_id，
  worker 取任务前检查，命中则跳过（对标业界成熟方案 任务取消）。
"""

import json
import time

from core.redis_client import get_redis

QUEUE_KEY = "xiongda:doc:queue"
PROCESSING_KEY = "xiongda:doc:processing"
CANCELLED_KEY = "xiongda:doc:cancelled"
# M5-2：延迟重试 zset，member = 任务 json，score = 该任务可再次执行的时刻（epoch 秒）。
# 用 zset 实现「退避到期」语义，worker 每轮把到期任务搬回主队列，无需额外定时器。
DELAYED_KEY = "xiongda:doc:delayed"

# 主流程（提取+向量化）单任务最大处理时长（秒），超过视为卡死、sweep 重新入队。
# 大文档向量化可能耗时数分钟，故设为 30 分钟，远大于正常处理时间。
PROCESS_MAX_AGE = 1800.0

# M5-2：主流程失败重试（对标业界 Asynq MaxRetry + 退避）
MAX_RETRY = 3                 # 首次失败后最多额外重试次数（共 MAX_RETRY+1 次尝试）
RETRY_BASE_DELAY = 30.0       # 指数退避基期（秒）
RETRY_MAX_DELAY = 600.0       # 退避上限（秒），避免间隔过长


def _dump(task: dict) -> str:
    return json.dumps(task, ensure_ascii=False)


async def enqueue(task: dict) -> None:
    """将主流程任务入队（持久化）。

    task 至少含 doc_id / kb_id / tenant_id / file_path / file_type / ai_config。
    """
    r = await get_redis()
    t = dict(task)
    t.setdefault("started_at", None)
    t.setdefault("retry", 0)
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
    """标记文档主流程任务已取消（文档被删除/取消时调用）。"""
    r = await get_redis()
    await r.sadd(CANCELLED_KEY, doc_id)


async def is_cancelled(doc_id: str) -> bool:
    """文档主流程任务是否已取消。"""
    r = await get_redis()
    return bool(await r.sismember(CANCELLED_KEY, doc_id))


async def sweep_stale(max_age: float = PROCESS_MAX_AGE) -> int:
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


def backoff_delay(attempt: int) -> float:
    """计算第 ``attempt`` 次失败后的退避延迟（秒，指数退避 + 上限）。

    第 0 次失败（首次尝试后）→ ``RETRY_BASE_DELAY``；每次失败后翻倍，封顶
    ``RETRY_MAX_DELAY``。用于 ``requeue_delayed`` 设置延迟重试时刻。
    """
    return min(RETRY_BASE_DELAY * (2 ** max(0, int(attempt))), RETRY_MAX_DELAY)


async def requeue_delayed(task: dict, retry_count: int, delay: float) -> None:
    """将失败任务按退避延迟重新入队（M5-2 重试核心）。

    从 processing 移除当前记录，写入 ``DELAYED_KEY``（zset，score = 现在 + delay），
    worker 每轮经 :func:`promote_delayed` 把到期任务搬回主队列再次执行。
    ``retry_count`` 写入任务的 ``retry`` 字段，供下次判断是否已达 ``MAX_RETRY``。
    """
    r = await get_redis()
    # 从 processing 移除（按 json 精确匹配当前 dequeued 记录）
    await r.lrem(PROCESSING_KEY, 1, _dump(task))
    t = dict(task)
    t["retry"] = retry_count
    t["started_at"] = None
    t["next_attempt_at"] = time.time() + delay
    await r.zadd(DELAYED_KEY, {_dump(t): t["next_attempt_at"]})


async def promote_delayed() -> int:
    """把到期的延迟重试任务搬回主队列（worker 每轮调用）。返回搬回数量。

    ``next_attempt_at <= now`` 的任务视为到期，移除 ``next_attempt_at`` 后重新入队。
    服务重启后这些任务仍留在 zset，到期即被搬回，崩溃可恢复。
    """
    r = await get_redis()
    now = time.time()
    items = await r.zrangebyscore(DELAYED_KEY, 0, now)
    moved = 0
    for raw in items:
        await r.zrem(DELAYED_KEY, raw)
        t = json.loads(raw)
        t.pop("next_attempt_at", None)
        await r.rpush(QUEUE_KEY, _dump(t))
        moved += 1
    return moved
