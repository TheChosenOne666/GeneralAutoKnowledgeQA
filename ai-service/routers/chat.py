"""聊天路由 — SSE 流式问答。

M2-5：接入 RAG 检索，先检索构建上下文，再 LLM 流式生成；推送 sources 事件（引用来源）。
M3-3：接收 Java 透传的 ai_config 真正消费用户模型；模型配置错误时推送 event: error
（error_type=MODEL_CONFIG_ERROR），由前端引导重新配置。
"""

import json
from dataclasses import asdict

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from loguru import logger
from pydantic import BaseModel

from services.agent import run_agent
from core.config import settings
from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError
from services.rag import rag_service
from services.web_search import web_search, format_search_results

router = APIRouter()


class ChatStreamRequest(BaseModel):
    """Java 后端转发的问答请求。"""

    question: str
    conversation_id: str = ""
    kb_ids: list[str] = []
    model: str = ""
    mode: str = "rag"  # rag / web / agent
    tenant_id: str = ""
    history: list[dict] = []  # 多轮对话历史（不含当前问题）
    ai_config: dict | None = None  # 用户 AI 模型配置（由 Java 透传）


@router.post("/chat/stream")
async def chat_stream(body: ChatStreamRequest):
    """SSE 流式问答 — Java 后端透传此接口给前端。

    M2-5：RAG 模式先检索知识库构建上下文，LLM 基于上下文流式生成，
    并推送 sources 事件携带引用来源（文件名 / 页码 / 内容片段）。
    M3-3：模型配置错误（无 Key / Key 错 / 模型名错 / 维度不匹配）时推送 event: error。
    """

    async def event_generator():
        yield _sse("thinking", {"content": "正在思考..."})

        logger.info(f"[M3-3诊断] 收到请求 mode={body.mode} kb_ids={body.kb_ids} "
                    f"ai_config是否为空={body.ai_config is None}")
        if body.ai_config:
            logger.info(f"[M3-3诊断] 收到ai_config字段={list(body.ai_config.keys())} "
                        f"llm_model={body.ai_config.get('llm_model')} "
                        f"embedding_model={body.ai_config.get('embedding_model')} "
                        f"rerank_provider={body.ai_config.get('rerank_provider')}")
        else:
            logger.warning("[M3-3诊断] ai_config为空，将走env兜底（易触发模型配置错误）")

        cfg = ModelConfig.from_dict(body.ai_config)
        if cfg is None:
            logger.warning("[M3-3诊断] ModelConfig为None，使用env兜底配置")
        else:
            logger.info(f"[M3-3诊断] ModelConfig已构建: llm={cfg.llm_provider}/{cfg.llm_model} "
                        f"emb={cfg.embedding_provider}/{cfg.embedding_model} "
                        f"has_llm_key={cfg.has_llm()} has_emb_key={cfg.has_embedding()}")
        # M4-1：Agent 多步推理模式（自研 ReAct 循环）
        if body.mode == "agent":
            try:
                async for evt in run_agent(
                    question=body.question,
                    kb_ids=body.kb_ids,
                    tenant_id=body.tenant_id,
                    model=body.model or None,
                    history=body.history,
                    cfg=cfg,
                ):
                    yield _sse(evt["event"], evt["data"])
            except Exception as e:
                logger.exception(f"Agent 执行异常: {e}")
                yield _sse("error", {"error_type": "AGENT_ERROR", "message": str(e)})
            yield _sse(
                "done",
                {"conversation_id": body.conversation_id, "sources": []},
            )
            return

        # M4-3：联网搜索模式
        context = ""
        sources = []
        if body.mode == "web":
            try:
                results = await web_search(
                    body.question,
                    max_results=settings.web_search_max_results,
                )
                if results:
                    sources = [
                        {
                            "source": r.get("title", "网络来源") or "网络来源",
                            "page": 0,
                            "content": r.get("snippet", "")[:300],
                            "score": 0.0,
                            "doc_id": r.get("url", ""),
                            "kb_id": "web",
                        }
                        for r in results
                    ]
                    context = format_search_results(results)
                else:
                    sources = []
                    context = ""
            except Exception as e:
                logger.warning(f"联网搜索失败，降级为无上下文问答: {e}")
                sources = []
                context = ""

        if body.mode == "rag":
            try:
                results = await rag_service.retrieve(
                    body.question, body.kb_ids, body.tenant_id, top_n=5, cfg=cfg, enhance=True
                )
                sources = [asdict(r) for r in results]
                if results:
                    context = "\n\n".join(
                        f"[来源：{r.source} 第{r.page}页]\n{r.content}" for r in results
                    )
            except ModelConfigError as e:
                logger.warning(f"模型配置错误（检索阶段）：{e}")
                yield _sse("error", {"error_type": "MODEL_CONFIG_ERROR", "message": str(e)})
                # 配置已确认错误，直接结束（不再无谓重试 LLM，避免重复 error 事件）
                yield _sse("done", {"conversation_id": body.conversation_id, "sources": []})
                return
            except Exception as e:
                logger.warning(f"RAG 检索失败，降级为无上下文问答: {e}")
                sources = []
                context = ""

        if sources:
            yield _sse("sources", {"sources": sources})

        # 检索/搜索无结果兜底：
        #  - fixed：直接返回固定文案，不调用 LLM（省成本）
        #  - model（默认）：交给 LLM 用通用知识兜底，并在 system 指令中声明无相关内容
        if not sources and settings.fallback_strategy == "fixed":
            logger.info(f"{body.mode} 模式无结果，走 fixed 兜底返回固定文案")
            yield _sse("token", {"content": settings.fallback_response})
            yield _sse(
                "done",
                {"conversation_id": body.conversation_id, "sources": []},
            )
            return

        try:
            async for token in llm_service.stream_generate(
                question=body.question,
                context=context,
                model=body.model or None,
                history=body.history,
                cfg=cfg,
                no_kb_content=not sources,
                context_source="kb" if body.mode == "rag" else "web",
            ):
                yield _sse("token", {"content": token})
        except ModelConfigError as e:
            logger.warning(f"模型配置错误（生成阶段）：{e}")
            yield _sse("error", {"error_type": "MODEL_CONFIG_ERROR", "message": str(e)})

        yield _sse(
            "done",
            {"conversation_id": body.conversation_id, "sources": sources},
        )

    return StreamingResponse(event_generator(), media_type="text/event-stream")


def _sse(event: str, data: dict) -> str:
    """格式化 SSE 消息。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
