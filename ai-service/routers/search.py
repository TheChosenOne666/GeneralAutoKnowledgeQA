"""全局搜索路由 — 文档 chunk（ES BM25）+ 聊天消息（ES BM25）。

为前端侧边栏全局搜索框提供统一搜索接口。

设计要点：
- 文档 chunk 搜索复用 ``ElasticsearchStore.keyword_search``（已含高亮、租户隔离）。
- 聊天消息搜索使用独立的消息 ES 索引 ``xiongda_msg_{tenant_id}``。
- 两者均走 ES BM25，不依赖向量召回（搜索场景关键词匹配更精准）。
- ES 不可用时返回空列表，不阻塞搜索请求（优雅降级）。
"""

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

from services.elasticsearch_store import get_es_store

router = APIRouter()


class SearchRequest(BaseModel):
    """全局搜索请求。"""

    query: str
    tenant_id: str
    kb_ids: list[str] | None = None  # 限定搜索的知识库范围（None = 全部）
    user_id: str | None = None  # 消息搜索按用户过滤
    top_k: int = 10


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


@router.post("/search/global")
async def global_search(body: SearchRequest) -> dict:
    """全局搜索：文档 chunk + 聊天消息。

    返回 ``{"documents": [...], "messages": [...]}``。
    ES 不可用时两个列表均返回空（优雅降级）。
    """
    documents: list[dict] = []
    messages: list[dict] = []

    es = get_es_store()
    if es is None:
        logger.debug("[搜索] ES 未启用，返回空结果")
        return {"documents": [], "messages": []}

    # --- 文档 chunk 搜索 ---
    try:
        results = await es.keyword_search(
            query=body.query,
            kb_ids=body.kb_ids or [],
            tenant_id=body.tenant_id,
            top_k=body.top_k,
        )
        for r in results:
            documents.append(
                {
                    "doc_id": r.doc_id,
                    "kb_id": r.kb_id,
                    "source": r.source,
                    "content": r.content[:200],  # 预览前 200 字
                    "highlight": "",  # keyword_search 暂未返回 highlight，后续增强
                    "score": r.score,
                }
            )
    except Exception as e:
        logger.warning(f"[搜索] 文档搜索失败: {e}")

    # --- 聊天消息搜索 ---
    try:
        msg_results = await es.search_messages(
            query=body.query,
            tenant_id=body.tenant_id,
            user_id=body.user_id,
            top_k=body.top_k,
        )
        for m in msg_results:
            messages.append(m)
    except Exception as e:
        logger.warning(f"[搜索] 消息搜索失败: {e}")

    return {"documents": documents, "messages": messages}


class IndexMessageRequest(BaseModel):
    """索引单条消息请求（Java 保存消息后异步调用）。"""

    message_id: str
    conversation_id: str
    conversation_title: str
    role: str  # user / assistant
    content: str
    tenant_id: str
    user_id: str
    create_time: str  # ISO 格式或时间戳字符串


@router.post("/search/index-message")
async def index_message(body: IndexMessageRequest) -> dict:
    """索引单条聊天消息到 ES（Java 保存消息后异步调用）。

    幂等：同 message_id 先删再插。
    """
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
