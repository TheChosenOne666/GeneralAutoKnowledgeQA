"""Redis 客户端单例 — 与 Java 后端共用同一 Redis 实例（默认 localhost:6379）。

采用 redis.asyncio 以支持检索 / 嵌入链路的异步调用。连接失败时由调用方降级处理，
不阻塞主流程（缓存是加速层，缺失不应导致问答失败）。
"""

import asyncio

import redis.asyncio as aioredis

from core.config import settings

_pool: aioredis.Redis | None = None
# get_redis 创建 _pool 时所在的事件循环。aioredis 客户端绑定首次连接的事件循环，
# 若后续在另一个事件循环（如测试里每个 TestClient 独立 portal、或服务 event-loop
# 重建）中复用该实例，会抛「Event loop is closed」。故记录创建时的 loop，跨 loop /
# loop 关闭时重建实例。
_pool_loop: asyncio.AbstractEventLoop | None = None


async def get_redis() -> aioredis.Redis:
    """获取 Redis 异步客户端单例（懒连接，事件循环切换时自动重建）。

    解决 aioredis 客户端绑定单一事件循环导致的「Event loop is closed」问题：
    当创建 _pool 的事件循环已关闭 / 与当前运行 loop 不一致时，重新创建实例，
    避免跨 TestClient 实例（每个独立 event loop）或 event-loop 重建时复用失效连接。
    """
    global _pool, _pool_loop
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = None
    if (
        _pool is None
        or _pool_loop is None
        or (loop is not None and _pool_loop is not loop)
        or (loop is None and _pool_loop.is_closed())
        or (_pool_loop is not None and _pool_loop.is_closed())
    ):
        _pool = aioredis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            db=settings.redis_db,
            decode_responses=True,
        )
        _pool_loop = loop
    return _pool
