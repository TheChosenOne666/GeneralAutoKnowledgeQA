"""熊答 AI 服务入口 — FastAPI + LangChain。

由 Java 后端通过 HTTP 调用，提供：
- /ai/chat/stream: SSE 流式问答（RAG + Agent）
- /ai/document/process: 文档处理（解析 → 分块 → 向量化 → 存储）
- /health: 健康检查
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from core.config import settings
from routers import chat, document


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"🤖 熊答 AI 服务启动中... (port={settings.app_port})")
    # TODO: 初始化 Milvus 连接、LLM 客户端
    yield
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


@app.get("/health")
async def health():
    return {"status": "ok", "service": "ai-service"}
