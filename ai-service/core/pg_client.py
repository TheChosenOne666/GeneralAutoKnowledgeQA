"""Postgres 异步连接池单例 — 向量持久化（pgvector）使用。

对齐 redis_client 的懒加载单例风格：首次调用时建立连接池，后续复用。
连接信息从配置读取（默认与 Java 后端同源），可通过 .env 覆盖。
"""

import asyncpg

from core.config import settings

_pool: asyncpg.Pool | None = None


async def get_pg_pool() -> asyncpg.Pool:
    """获取 Postgres 异步连接池单例（懒加载）。"""
    global _pool
    if _pool is None:
        _pool = await asyncpg.create_pool(
            host=settings.pg_host,
            port=settings.pg_port,
            user=settings.pg_user,
            password=settings.pg_password,
            database=settings.pg_db,
            min_size=1,
            max_size=5,
        )
    return _pool


async def close_pg_pool() -> None:
    """关闭连接池（应用关闭时调用）。"""
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None
