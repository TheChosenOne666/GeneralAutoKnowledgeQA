"""聊天路由 — SSE 流式问答。

M1-8：无 RAG，LLM 直接回答。M2 接入 RAG 检索后自动增强。
"""

import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from services.llm import llm_service

router = APIRouter()


class ChatStreamRequest(BaseModel):
    """Java 后端转发的问答请求。"""

    question: str
    conversation_id: str = ""
    kb_ids: list[str] = []
    model: str = ""
    mode: str = "rag"  # rag / search
    tenant_id: str = ""


@router.post("/chat/stream")
async def chat_stream(body: ChatStreamRequest):
    """SSE 流式问答 — Java 后端透传此接口给前端。

    M1-8：直接调用 LLM 流式生成（无 RAG 检索）。
    M2 将接入 RAG：检索 → 构建上下文 → LLM 生成。
    """

    async def event_generator():
        yield _sse("thinking", {"content": "正在思考..."})

        # M1-8：无 RAG，直接调 LLM
        # M2 将在此处添加 RAG 检索逻辑
        async for token in llm_service.stream_generate(
            question=body.question,
            context="",
            model=body.model or None,
        ):
            yield _sse("token", {"content": token})

        yield _sse("done", {"conversation_id": body.conversation_id})

    return StreamingResponse(event_generator(), media_type="text/event-stream")


def _sse(event: str, data: dict) -> str:
    """格式化 SSE 消息。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
