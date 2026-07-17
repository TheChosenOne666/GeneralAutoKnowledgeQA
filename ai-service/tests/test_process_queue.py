"""文档处理主流程持久化队列单测（M5-1：enqueue/dequeue/cancel/sweep + worker 行为）。

用内存版异步 fake Redis 模拟 Redis 行为（不依赖外部实例 / fakeredis 传输兼容问题）。
仅实现 process_queue 实际用到的命令：rpush / rpoplpush / lrem / lrange / sadd / sismember。
"""
import asyncio
import json
import time

import pytest

from services import process_queue as pq
import services.document_processor as dp_mod
from services.document_processor import document_processor, DocumentProcessor
from services.model_config import ModelConfigError


class _FakeRedis:
    """极简内存版异步 Redis，覆盖 process_queue 用到的命令。"""

    def __init__(self):
        self.data = {
            pq.QUEUE_KEY: [],
            pq.PROCESSING_KEY: [],
            pq.CANCELLED_KEY: set(),
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
            pq.QUEUE_KEY: [],
            pq.PROCESSING_KEY: [],
            pq.CANCELLED_KEY: set(),
        }


@pytest.fixture
def fake_redis(monkeypatch):
    fake = _FakeRedis()

    async def fake_get_redis():
        return fake

    monkeypatch.setattr(pq, "get_redis", fake_get_redis)
    yield fake
    asyncio.run(fake.flushall())


def _run(coro):
    return asyncio.run(coro)


def test_enqueue_dequeue(fake_redis):
    async def main():
        await pq.enqueue({"doc_id": "d1"})
        t = await pq.dequeue()
        assert t["doc_id"] == "d1"
        assert t["started_at"] is not None
        assert await pq.dequeue() is None
        await pq.ack(t)

    _run(main())


def test_cancelled_set(fake_redis):
    async def main():
        await pq.mark_cancelled("d1")
        assert await pq.is_cancelled("d1")
        assert not await pq.is_cancelled("d2")

    _run(main())


def test_sweep_stale_moves_timeout_back(fake_redis):
    async def main():
        await pq.enqueue({"doc_id": "d1"})
        t = await pq.dequeue()
        t["started_at"] = time.time() - 1000
        await fake_redis.lrem(pq.PROCESSING_KEY, 1, json.dumps(t, ensure_ascii=False))
        await fake_redis.rpush(pq.PROCESSING_KEY, json.dumps(t, ensure_ascii=False))
        moved = await pq.sweep_stale(max_age=10)
        assert moved == 1
        q_items = await fake_redis.lrange(pq.QUEUE_KEY, 0, -1)
        assert json.loads(q_items[0])["started_at"] is None
        t2 = await pq.dequeue()
        assert t2["doc_id"] == "d1"
        await pq.ack(t2)

    _run(main())


def test_sweep_keeps_recent(fake_redis):
    async def main():
        await pq.enqueue({"doc_id": "d1"})
        await pq.dequeue()
        moved = await pq.sweep_stale(max_age=600)
        assert moved == 0

    _run(main())


def test_worker_skips_cancelled(fake_redis, monkeypatch):
    """已取消文档的任务应被 worker 跳过，不执行 process()。"""
    async def main():
        await pq.mark_cancelled("d1")
        await pq.enqueue({"doc_id": "d1", "file_path": "/x", "file_type": "pdf",
                          "kb_id": "k1", "tenant_id": "t1"})

        called = {"process": False}

        async def fake_process(self, **kwargs):
            called["process"] = True

        async def fake_notify(*args, **kwargs):
            pass

        monkeypatch.setattr(DocumentProcessor, "process", fake_process)
        monkeypatch.setattr(dp_mod, "notify_document_status", fake_notify)

        t = await pq.dequeue()
        await document_processor._run_process_task(t)
        assert called["process"] is False
        # 跳过后仍 ack 移除
        assert await pq.dequeue() is None

    _run(main())


def test_worker_notifies_failed_on_model_config_error(fake_redis, monkeypatch):
    """process() 抛 ModelConfigError 时，worker 应回调 failed 且标记 modelConfigError。"""
    async def main():
        await pq.enqueue({"doc_id": "d1", "file_path": "/x", "file_type": "pdf",
                          "kb_id": "k1", "tenant_id": "t1"})

        async def fake_process(self, **kwargs):
            raise ModelConfigError("embedding 维度不匹配")

        calls = []

        async def fake_notify(doc_id, status, **kwargs):
            calls.append((doc_id, status, kwargs))

        monkeypatch.setattr(DocumentProcessor, "process", fake_process)
        monkeypatch.setattr(dp_mod, "notify_document_status", fake_notify)

        t = await pq.dequeue()
        await document_processor._run_process_task(t)
        assert calls, "应回调一次状态"
        doc_id, status, kwargs = calls[-1]
        assert status == "failed"
        assert kwargs.get("model_config_error") is True

    _run(main())
