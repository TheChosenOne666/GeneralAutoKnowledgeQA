"""聊天路由 — SSE 流式问答。

M2-5：接入 RAG 检索，先检索构建上下文，再 LLM 流式生成；推送 sources 事件（引用来源）。
M3-3：接收 Java 透传的 ai_config 真正消费用户模型；模型配置错误时推送 event: error
（error_type=MODEL_CONFIG_ERROR），由前端引导重新配置。
"""

import json
import os
from dataclasses import asdict

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from loguru import logger
from pydantic import BaseModel

from services.agent import run_agent
from services.document_processor import document_processor
from core.config import settings
from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError, ModelQuotaError
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
    # M5-9 多模态问答：图片绝对路径列表（Python 转 base64 调 LLM vision）
    image_paths: list[str] = []
    # M5-9 一次性文档问答：通用文档绝对路径列表（Python 提取文本拼到 LLM 上下文，不入向量库）
    attachment_paths: list[str] = []


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
                    # M5-5 父子分块：喂 LLM 的上下文优先用回溯到的父块完整内容（更连贯），
                    # 引用来源（sources）仍用子块 content 精确定位命中片段。
                    context = "\n\n".join(
                        f"[来源：{r.source} 第{r.page}页]\n{r.parent_content or r.content}"
                        for r in results
                    )
            except ModelQuotaError as e:
                logger.warning(f"模型额度/限流错误（检索阶段）：{e}")
                yield _sse("error", {"error_type": "QUOTA_ERROR", "message": str(e)})
                # 额度 / 限流，直接结束（提示用户稍后重试，而非重配模型）
                yield _sse("done", {"conversation_id": body.conversation_id, "sources": []})
                return
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

        # M5-9 一次性文档问答：把 attachment_paths 提取的文本拼到 context，
        # 不入向量库、不跨会话。仅在 rag/web 模式生效（agent 模式由其自身工具链决定）。
        if body.attachment_paths and body.mode in ("rag", "web"):
            try:
                att_text = await _extract_attachment_text(body.attachment_paths)
                if att_text:
                    context = (context + "\n\n" + att_text) if context else att_text
                    logger.info(f"[M5-9] 附件提取文本拼接 len={len(att_text)} files={len(body.attachment_paths)}")
            except Exception as e:
                logger.warning(f"[M5-9] 附件提取失败，忽略：{e}")

        # 检索/搜索无结果兜底：
        #  - fixed：直接返回固定文案，不调用 LLM（省成本）
        #  - model（默认）：交给 LLM 用通用知识兜底，并在 system 指令中声明无相关内容
        # M5-9：携带图片或附件时强制走 model 路径（用户明确上传了内容，不能因知识库无结果就返回 fixed 兜底）
        has_user_attachments = bool(body.image_paths) or bool(body.attachment_paths)
        if not sources and not has_user_attachments and settings.fallback_strategy == "fixed":
            logger.info(f"{body.mode} 模式无结果，走 fixed 兜底返回固定文案")
            yield _sse("token", {"content": settings.fallback_response})
            yield _sse(
                "done",
                {"conversation_id": body.conversation_id, "sources": []},
            )
            return

        try:
            # M5-9 多模态：携带图片时走 vision 分支（OpenAI vision API 兼容），
            # 否则走普通文本生成。
            if body.image_paths and body.mode in ("rag", "web"):
                async for token in llm_service.stream_generate_with_images(
                    question=body.question,
                    image_paths=body.image_paths,
                    context=context,
                    model=body.model or None,
                    history=body.history,
                    cfg=cfg,
                ):
                    yield _sse("token", {"content": token})
            else:
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
        except ModelQuotaError as e:
            logger.warning(f"模型额度/限流错误（生成阶段）：{e}")
            yield _sse("error", {"error_type": "QUOTA_ERROR", "message": str(e)})
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


async def _extract_attachment_text(paths: list[str]) -> str:
    """从附件文件路径提取文本拼接（M5-9 一次性文档问答，不入向量库，仅本次问答用）。

    复用 document_processor.extract_pages 提取分页文本（PDF/DOCX/TXT/MD），
    非 supported 类型跳过并记录 warning。
    """
    if not paths:
        return ""
    parts: list[str] = []
    supported_exts = ("pdf", "docx", "txt", "md")
    for path in paths:
        try:
            ext = path.lower().rsplit(".", 1)[-1] if "." in path else ""
            if ext not in supported_exts:
                logger.warning(f"[M5-9] 附件类型不支持提取: {path} ext={ext}")
                continue
            pages = await document_processor.extract_pages(path, ext)
            if pages:
                text = "\n".join(p.text for p in pages)
                # 文件名仅取 basename，避免绝对路径泄露
                name = os.path.basename(path)
                parts.append(f"[附件: {name}]\n{text}")
        except Exception as e:
            logger.warning(f"[M5-9] 附件提取失败 path={path}: {e}")
            continue
    return "\n\n".join(parts)
