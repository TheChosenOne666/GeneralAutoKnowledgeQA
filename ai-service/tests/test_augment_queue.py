"""问答增强持久化队列单测（enqueue/dequeue/cancel/sweep）。

用内存版异步 fake Redis 模拟 Redis 行为（不依赖外部实例，也不依赖 fakeredis 的
aioredis 传输实现——该实现在部分 Windows/Python 环境下存在 transport 兼容问题）。
仅实现 augment_queue 实际用到的命令：rpush / rpoplpush / lrem / lrange / sadd / sismember。
"""
import asyncio
import json
import time

import pytest

from services import augment_queue as aq


class _FakeRedis:
    """极简内存版异步 Redis，覆盖 augment_queue 用到的命令。"""

    def __init__(self):
        self.data = {
            aq.QUEUE_KEY: [],
            aq.PROCESSING_KEY: [],
            aq.CANCELLED_KEY: set(),
        }

    async def rpush(self, key, value):
        self.data.setdefault(key, []).append(value)

    async def rpoplpush(self, src, dst):
        lst = self.data.setdefault(src, [])
        if not lst:
            return None
        v = lst.pop(0)
        self.data.setdefault(dst, []).append(v)
        return v

    async def lrem(self, key, count, value):
        lst = self.data.setdefault(key, [])
        if value in lst:
            lst.remove(value)

    async def lrange(self, key, start, end):
        return list(self.data.get(key, []))

    async def sadd(self, key, value):
        self.data.setdefault(key, set()).add(value)

    async def sismember(self, key, value):
        return value in self.data.get(key, set())

    async def flushall(self):
        self.data = {
            aq.QUEUE_KEY: [],
            aq.PROCESSING_KEY: [],
            aq.CANCELLED_KEY: set(),
        }


@pytest.fixture
def fake_redis(monkeypatch):
    fake = _FakeRedis()

    async def fake_get_redis():
        return fake

    monkeypatch.setattr(aq, "get_redis", fake_get_redis)
    yield fake
    asyncio.run(fake.flushall())


def _run(coro):
    return asyncio.run(coro)


def test_enqueue_dequeue(fake_redis):
    async def main():
        await aq.enqueue({"doc_id": "d1"})
        t = await aq.dequeue()
        assert t["doc_id"] == "d1"
        assert t["started_at"] is not None
        # 空队列返回 None
        assert await aq.dequeue() is None
        await aq.ack(t)

    _run(main())


def test_cancelled_set(fake_redis):
    async def main():
        await aq.mark_cancelled("d1")
        assert await aq.is_cancelled("d1")
        assert not await aq.is_cancelled("d2")

    _run(main())


def test_sweep_stale_moves_timeout_back(fake_redis):
    async def main():
        await aq.enqueue({"doc_id": "d1"})
        t = await aq.dequeue()  # 移入 processing 带 started_at
        # 模拟卡死：把 started_at 改很早并重写 processing 记录
        t["started_at"] = time.time() - 1000
        await fake_redis.lrem(aq.PROCESSING_KEY, 1, json.dumps(t, ensure_ascii=False))
        await fake_redis.rpush(aq.PROCESSING_KEY, json.dumps(t, ensure_ascii=False))
        moved = await aq.sweep_stale(max_age=10)
        assert moved == 1
        # 重新入队的任务 started_at 被重置为 None
        q_items = await fake_redis.lrange(aq.QUEUE_KEY, 0, -1)
        assert json.loads(q_items[0])["started_at"] is None
        # 重新出队得到同一任务
        t2 = await aq.dequeue()
        assert t2["doc_id"] == "d1"
        await aq.ack(t2)

    _run(main())


def test_sweep_keeps_recent(fake_redis):
    async def main():
        await aq.enqueue({"doc_id": "d1"})
        await aq.dequeue()  # processing 中，刚取（started_at 新）
        moved = await aq.sweep_stale(max_age=600)
        assert moved == 0

    _run(main())
