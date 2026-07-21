"""熊答 AI 服务入口 — FastAPI + LangChain。

由 Java 后端通过 HTTP 调用，提供：
- /ai/chat/stream: SSE 流式问答（RAG + Agent）
- /ai/document/process: 文档处理（解析 → 分块 → 向量化 → 存储）
- /health: 健康检查
"""

import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from core.config import settings
from core.pg_client import close_pg_pool, get_pg_pool
from routers import admin, cache, chat, document
from services.augment_queue import sweep_stale
from services.document_processor import document_processor
from services.process_queue import sweep_stale as sweep_process_stale
from services.elasticsearch_store import close_es, get_es_store
from services.vector_store import PgVectorStore, ensure_embeddings_table, vector_store_service


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"🤖 熊答 AI 服务启动中... (port={settings.app_port})")
    # 向量持久化初始化：建表 + BM25 预热（重启不丢知识）
    try:
        pool = await get_pg_pool()
        await ensure_embeddings_table(pool)
        if isinstance(vector_store_service, PgVectorStore):
            await vector_store_service.warmup_bm25()
        logger.info("✅ 向量持久化（pgvector）初始化完成")
    except Exception as e:
        logger.error(f"❌ 向量持久化初始化失败（检索将不可用）: {e}")
    # M5-8：Elasticsearch 检索引擎（若启用）。构造即建立连接；启动补迁「PG 有数据但 ES 缺失」的租户
    if settings.elasticsearch_enabled:
        try:
            es = get_es_store()
            if es and settings.elasticsearch_auto_reindex_on_empty:
                await es.auto_reindex_missing()
            logger.info("✅ Elasticsearch 检索引擎已就绪" if es else "⚠️ ES 未初始化")
        except Exception as e:
            logger.error(f"❌ Elasticsearch 初始化失败（检索回退 pgvector+BM25）: {e}")
    # 问答增强队列：恢复上次崩溃残留的 processing 任务，并启动常驻 worker
    # （对齐 业界 finalizing（异步增强） 任务队列：持久化、重启可恢复、不丢任务）
    try:
        moved = await sweep_stale()
        if moved:
            logger.info(f"♻️ 增强队列恢复：{moved} 个卡死任务重新入队")
    except Exception as e:
        logger.warning(f"⚠️ 增强队列 sweep 失败（不影响启动）: {e}")
    # M5-1：主流程队列同样持久化、重启可恢复
    try:
        moved = await sweep_process_stale()
        if moved:
            logger.info(f"♻️ 主流程队列恢复：{moved} 个卡死任务重新入队")
    except Exception as e:
        logger.warning(f"⚠️ 主流程队列 sweep 失败（不影响启动）: {e}")
    augment_worker_task = asyncio.create_task(document_processor.run_augment_worker())
    logger.info("🚀 问答增强队列 worker 已启动")
    process_worker_task = asyncio.create_task(document_processor.run_process_worker())
    logger.info("🚀 文档主流程队列 worker 已启动")
    yield
    augment_worker_task.cancel()
    try:
        await augment_worker_task
    except asyncio.CancelledError:
        pass
    process_worker_task.cancel()
    try:
        await process_worker_task
    except asyncio.CancelledError:
        pass
    await close_es()
    await close_pg_pool()
    logger.info("👋 熊答 AI 服务已关闭")


app = FastAPI(
    title="熊答 AI 服务",
    description="RAG + Agent 智能问答引擎（Python + LangChain）",
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(chat.router, prefix="/ai")
app.include_router(document.router, prefix="/ai")
app.include_router(cache.router, prefix="/ai")
app.include_router(admin.router, prefix="/ai")
app.include_router(search.router, prefix="/ai")


@app.get("/health")
async def health():
    return {"status": "ok", "service": "ai-service"}
