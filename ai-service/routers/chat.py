"""聊天路由 — SSE 流式问答。

接收 Java 后端的请求，调用 LangChain RAG + Agent 生成流式回答。
"""

import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from services.rag import rag_service
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

    流程:
    1. RAG 检索（向量化 query → 混合检索 → Rerank 精排）
    2. 构建 Prompt（检索结果 + 系统提示）
    3. LangChain LLM 流式生成
    4. SSE 推送每个 token 给 Java → 前端
    """

    async def event_generator():
        # 1. 检索知识库
        yield _sse("thinking", {"content": "正在检索知识库..."})

        sources = []
        if body.kb_ids and body.mode == "rag":
            try:
                results = await rag_service.retrieve(
                    query=body.question,
                    kb_ids=body.kb_ids,
                    tenant_id=body.tenant_id,
                )
                sources = [
                    {"filename": r.source, "page": r.page, "score": r.score}
                    for r in results
                ]
                if sources:
                    yield _sse("sources", {"sources": sources})
            except NotImplementedError:
                # AI 服务骨架阶段，跳过检索
                pass

        # 2. 流式生成回答
        context_text = "\n\n".join(r.content for r in (results if "results" in dir() else []))
        async for token in llm_service.stream_generate(
            question=body.question,
            context=context_text,
            model=body.model or None,
        ):
            yield _sse("token", {"content": token})

        yield _sse("done", {"conversation_id": body.conversation_id})

    return StreamingResponse(event_generator(), media_type="text/event-stream")


def _sse(event: str, data: dict) -> str:
    """格式化 SSE 消息。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
