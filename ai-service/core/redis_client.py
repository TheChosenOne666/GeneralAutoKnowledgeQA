"""Redis 客户端单例 — 与 Java 后端共用同一 Redis 实例（默认 localhost:6379）。

采用 redis.asyncio 以支持检索 / 嵌入链路的异步调用。连接失败时由调用方降级处理，
不阻塞主流程（缓存是加速层，缺失不应导致问答失败）。
"""

import redis.asyncio as aioredis

from core.config import settings

_pool: aioredis.Redis | None = None


async def get_redis() -> aioredis.Redis:
    """获取 Redis 异步客户端单例（懒连接）。"""
    global _pool
    if _pool is None:
        _pool = aioredis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            db=settings.redis_db,
            decode_responses=True,
        )
    return _pool
