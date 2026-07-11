"""聊天路由 — SSE 流式问答。

M2-5：接入 RAG 检索，先检索构建上下文，再 LLM 流式生成；推送 sources 事件（引用来源）。
"""

import json
from dataclasses import asdict

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from loguru import logger
from pydantic import BaseModel

from services.llm import llm_service
from services.rag import rag_service

router = APIRouter()


class ChatStreamRequest(BaseModel):
    """Java 后端转发的问答请求。"""

    question: str
    conversation_id: str = ""
    kb_ids: list[str] = []
    model: str = ""
    mode: str = "rag"  # rag / search
    tenant_id: str = ""
    history: list[dict] = []  # 多轮对话历史（不含当前问题）


@router.post("/chat/stream")
async def chat_stream(body: ChatStreamRequest):
    """SSE 流式问答 — Java 后端透传此接口给前端。

    M2-5：RAG 模式先检索知识库构建上下文，LLM 基于上下文流式生成，
    并推送 sources 事件携带引用来源（文件名 / 页码 / 内容片段）。
    """

    async def event_generator():
        yield _sse("thinking", {"content": "正在思考..."})

        context = ""
        sources = []
        if body.mode == "rag":
            try:
                results = await rag_service.retrieve(
                    body.question, body.kb_ids, body.tenant_id, top_n=5
                )
                sources = [asdict(r) for r in results]
                if results:
                    context = "\n\n".join(
                        f"[来源：{r.source} 第{r.page}页]\n{r.content}" for r in results
                    )
            except Exception as e:
                logger.warning(f"RAG 检索失败，降级为无上下文问答: {e}")
                sources = []
                context = ""

        if sources:
            yield _sse("sources", {"sources": sources})

        async for token in llm_service.stream_generate(
            question=body.question,
            context=context,
            model=body.model or None,
            history=body.history,
        ):
            yield _sse("token", {"content": token})

        yield _sse(
            "done",
            {"conversation_id": body.conversation_id, "sources": sources},
        )

    return StreamingResponse(event_generator(), media_type="text/event-stream")


def _sse(event: str, data: dict) -> str:
    """格式化 SSE 消息。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
