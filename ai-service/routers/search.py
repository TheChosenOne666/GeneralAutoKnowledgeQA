"""全局搜索路由 — 文档 chunk（ES BM25 + 向量 kNN RRF 融合）+ 聊天消息（ES BM25 多字段）。

为前端侧边栏全局搜索框提供统一搜索接口。

设计要点：
- 文档 chunk 搜索：BM25 多字段（content + source）+ 可选向量 kNN 召回 + RRF 融合。
- 聊天消息搜索：BM25 多字段（content + conversation_title）。
- 支持搜索运算符：引号 "精确短语"、减号 -排除词。
- 支持 from + size 分页。
- ES 不可用时返回空列表，不阻塞搜索请求（优雅降级）。
"""

import re

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

from services.elasticsearch_store import get_es_store

router = APIRouter()


# ==================== 搜索运算符解析 ====================

def parse_search_operators(query: str) -> dict:
    """解析搜索运算符。

    支持的语法：
    - ``"精确短语"`` → match_phrase
    - ``-排除词`` → must_not
    - 普通词 → match (BM25)

    返回 ``{"phrase": str|None, "positive": [str], "negative": [str], "raw": str}``。
    """
    raw = query.strip()
    phrase: str | None = None
    negative: list[str] = []
    positive: list[str] = []

    # 提取引号内的精确短语
    phrase_match = re.search(r'"([^"]+)"', raw)
    if phrase_match:
        phrase = phrase_match.group(1)
        raw = raw.replace(phrase_match.group(0), " ")

    # 分词
    tokens = raw.split()
    for token in tokens:
        if token.startswith("-") and len(token) > 1:
            negative.append(token[1:])
        elif token.startswith("+") and len(token) > 1:
            positive.append(token[1:])
        elif token:
            positive.append(token)

    return {
        "phrase": phrase,
        "positive": positive,
        "negative": negative,
        "raw": query,
    }


# ==================== 请求/响应模型 ====================


class SearchRequest(BaseModel):
    """全局搜索请求。"""

    query: str
    tenant_id: str
    kb_ids: list[str] | None = None
    user_id: str | None = None
    top_k: int = 10
    from_: int = 0  # 分页偏移
    enable_semantic: bool = True  # 是否启用向量召回融合


class DocResult(BaseModel):
    doc_id: str
    kb_id: str
    source: str
    content: str
    highlight: str
    score: float


class MsgResult(BaseModel):
    id: str
    conversation_id: str
    conversation_title: str
    role: str
    content: str
    highlight: str
    score: float


class SearchResponse(BaseModel):
    documents: list[DocResult]
    messages: list[MsgResult]
    total_documents: int
    total_messages: int


@router.post("/search/global")
async def global_search(body: SearchRequest) -> dict:
    """全局搜索：文档 chunk（BM25 + 向量融合）+ 聊天消息（BM25 多字段）。

    返回 ``{"documents": [...], "messages": [...], "total_documents": int, "total_messages": int}``。
    """
    documents: list[dict] = []
    messages: list[dict] = []
    total_docs = 0
    total_msgs = 0

    es = get_es_store()
    if es is None:
        logger.debug("[搜索] ES 未启用，返回空结果")
        return {"documents": [], "messages": [], "total_documents": 0, "total_messages": 0}

    # --- 文档 chunk 搜索（BM25 + 可选向量融合） ---
    try:
        # 生成 query embedding（用于向量召回）
        embedding = None
        if body.enable_semantic:
            try:
                from services.embedding import EmbeddingService
                from core.config import settings

                emb_service = EmbeddingService()
                embedding = await emb_service.embed_text(body.query)
            except Exception as e:
                logger.debug(f"[搜索] 向量生成失败，仅用 BM25: {e}")
                embedding = None

        result = await es.search_documents_enhanced(
            query=body.query,
            tenant_id=body.tenant_id,
            kb_ids=body.kb_ids,
            top_k=body.top_k,
            from_=body.from_,
            embedding=embedding,
        )
        documents = result.get("hits", [])
        total_docs = result.get("total", 0)
    except Exception as e:
        logger.warning(f"[搜索] 文档搜索失败: {e}")

    # --- 聊天消息搜索（BM25 多字段） ---
    try:
        msg_result = await es.search_messages(
            query=body.query,
            tenant_id=body.tenant_id,
            user_id=body.user_id,
            top_k=body.top_k,
            from_=body.from_,
        )
        messages = msg_result.get("hits", [])
        total_msgs = msg_result.get("total", 0)
    except Exception as e:
        logger.warning(f"[搜索] 消息搜索失败: {e}")

    return {
        "documents": documents,
        "messages": messages,
        "total_documents": total_docs,
        "total_messages": total_msgs,
    }


class IndexMessageRequest(BaseModel):
    """索引单条消息请求（Java 保存消息后异步调用）。"""

    message_id: str
    conversation_id: str
    conversation_title: str
    role: str
    content: str
    tenant_id: str
    user_id: str
    create_time: str


@router.post("/search/index-message")
async def index_message(body: IndexMessageRequest) -> dict:
    """索引单条聊天消息到 ES（Java 保存消息后异步调用）。"""
    es = get_es_store()
    if es is None:
        return {"status": "skipped", "reason": "es_disabled"}

    try:
        await es.index_message(
            message_id=body.message_id,
            conversation_id=body.conversation_id,
            conversation_title=body.conversation_title,
            role=body.role,
            content=body.content,
            tenant_id=body.tenant_id,
            user_id=body.user_id,
            create_time=body.create_time,
        )
        return {"status": "ok"}
    except Exception as e:
        logger.warning(f"[搜索] 索引消息失败: {e}")
        return {"status": "failed", "error": str(e)}


@router.delete("/search/messages/{conversation_id}")
async def delete_conversation_messages(conversation_id: str, tenant_id: str) -> dict:
    """删除某会话的所有消息索引（会话删除时调用）。"""
    es = get_es_store()
    if es is None:
        return {"status": "skipped"}

    try:
        await es.delete_messages_by_conversation(conversation_id, tenant_id)
        return {"status": "ok"}
    except Exception as e:
        logger.warning(f"[搜索] 删除会话消息索引失败: {e}")
        return {"status": "failed", "error": str(e)}


@router.get("/search/operators")
async def search_operators_help() -> dict:
    """搜索运算符使用说明。"""
    return {
        "operators": [
            {"syntax": '"精确短语"', "description": "用双引号包裹的词将进行精确短语匹配", "example": '"请假申请"'},
            {"syntax": "-排除词", "description": "减号前缀的词将从结果中排除", "example": "休假 -年假"},
            {"syntax": "+必含词", "description": "加号前缀的词必须出现在结果中", "example": "请假 +申请"},
            {"syntax": "普通词", "description": "多个普通词之间为 OR 关系（BM25 评分排序）", "example": "请假 休假"},
        ],
        "fields": {
            "documents": ["content", "source（文件名）"],
            "messages": ["content", "conversation_title（会话标题）"],
        },
        "features": [
            "BM25 多字段全文检索",
            "向量语义召回 + RRF 融合（文档搜索）",
            "搜索运算符（精确短语、排除、必含）",
            "分页（from + top_k）",
            "高亮片段",
        ],
    }
