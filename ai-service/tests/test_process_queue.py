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
            pq.DELAYED_KEY: [],  # 存 [(score, member), ...]
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

    async def zadd(self, key, mapping):
        lst = self.data.setdefault(key, [])
        for member, score in mapping.items():
            lst.append((score, member))

    async def zrangebyscore(self, key, min_, max_):
        lst = self.data.get(key, [])
        return [member for score, member in lst if min_ <= score <= max_]

    async def zrem(self, key, member):
        lst = self.data.setdefault(key, [])
        self.data[key] = [(s, m) for s, m in lst if m != member]

    async def flushall(self):
        self.data = {
            pq.QUEUE_KEY: [],
            pq.PROCESSING_KEY: [],
            pq.CANCELLED_KEY: set(),
            pq.DELAYED_KEY: [],
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
        # 模型配置错误不可重试：任务应被 ack 移除，不再重新入队
        assert await pq.dequeue() is None

    _run(main())


def test_backoff_delay():
    """指数退避：基期 × 2^attempt，封顶 RETRY_MAX_DELAY。"""
    assert pq.backoff_delay(0) == pq.RETRY_BASE_DELAY
    assert pq.backoff_delay(1) == pq.RETRY_BASE_DELAY * 2
    assert pq.backoff_delay(2) == pq.RETRY_BASE_DELAY * 4
    assert pq.backoff_delay(10) == pq.RETRY_MAX_DELAY


def test_requeue_delayed_then_promote(fake_redis):
    """失败任务经 requeue_delayed 进入延迟 zset，promote_delayed 到期搬回主队列。"""
    async def main():
        await pq.enqueue({"doc_id": "d1"})
        t = await pq.dequeue()
        # 任务当前在 processing，requeue 后应移出
        assert json.loads((await fake_redis.lrange(pq.PROCESSING_KEY, 0, -1))[0])["doc_id"] == "d1"
        await pq.requeue_delayed(t, retry_count=1, delay=0.0)
        # 已从 processing 移除，且进入 delayed zset
        assert await fake_redis.lrange(pq.PROCESSING_KEY, 0, -1) == []
        delayed = fake_redis.data[pq.DELAYED_KEY]
        assert delayed, "应进入延迟队列"
        # promote 到期任务（delay=0，立即到期）搬回主队列，并去掉 next_attempt_at
        moved = await pq.promote_delayed()
        assert moved == 1
        assert fake_redis.data[pq.DELAYED_KEY] == []
        t2 = await pq.dequeue()
        assert t2["doc_id"] == "d1"
        assert t2["retry"] == 1
        assert "next_attempt_at" not in t2
        await pq.ack(t2)

    _run(main())


def test_worker_retries_then_fails(fake_redis, monkeypatch):
    """瞬时异常应重试最多 MAX_RETRY 次，耗尽后回调 failed（非模型配置错误）。"""
    async def main():
        await pq.enqueue({"doc_id": "d1", "file_path": "/x", "file_type": "pdf",
                          "kb_id": "k1", "tenant_id": "t1"})
        attempts = {"n": 0}

        async def fake_process(self, **kwargs):
            attempts["n"] += 1
            raise RuntimeError("瞬时 DB 连接失败")

        calls = []

        async def fake_notify(doc_id, status, **kwargs):
            calls.append((doc_id, status, dict(kwargs)))

        monkeypatch.setattr(DocumentProcessor, "process", fake_process)
        monkeypatch.setattr(dp_mod, "notify_document_status", fake_notify)
        # 退避置 0，使延迟重试任务立即到期可被 promote 搬回（测重试计数而非真实等待）
        monkeypatch.setattr(pq, "backoff_delay", lambda attempt: 0.0)

        # 跑满 MAX_RETRY+1 次尝试：每次失败 → 退避延迟重入队 → promote 搬回 → 再执行
        for _ in range(pq.MAX_RETRY + 1):
            t = await pq.dequeue()
            if t is None:
                await pq.promote_delayed()
                t = await pq.dequeue()
            assert t is not None, "重试任务应保持可消费"
            await document_processor._run_process_task(t)

        assert attempts["n"] == pq.MAX_RETRY + 1, "应执行满首次 + MAX_RETRY 次重试"
        failed = [c for c in calls if c[1] == "failed"]
        assert len(failed) == 1, "仅最终失败回调一次"
        assert failed[0][2].get("model_config_error") is not True
        assert "已重试" in (failed[0][2].get("error_msg") or "")
        # 最终失败后被 ack，队列清空
        assert await pq.dequeue() is None

    _run(main())


def test_worker_does_not_retry_model_config_error(fake_redis, monkeypatch):
    """模型配置错误即使有 retry 字段也不重试，直接 failed。"""
    async def main():
        await pq.enqueue({"doc_id": "d1", "retry": 0, "file_path": "/x", "file_type": "pdf",
                          "kb_id": "k1", "tenant_id": "t1"})
        attempts = {"n": 0}

        async def fake_process(self, **kwargs):
            attempts["n"] += 1
            raise ModelConfigError("embedding 维度不匹配")

        calls = []

        async def fake_notify(doc_id, status, **kwargs):
            calls.append((doc_id, status, dict(kwargs)))

        monkeypatch.setattr(DocumentProcessor, "process", fake_process)
        monkeypatch.setattr(dp_mod, "notify_document_status", fake_notify)

        t = await pq.dequeue()
        await document_processor._run_process_task(t)
        assert attempts["n"] == 1, "仅执行一次"
        assert len([c for c in calls if c[1] == "failed"]) == 1
        assert calls[-1][2].get("model_config_error") is True
        assert await pq.dequeue() is None, "不应重新入队"

    _run(main())
