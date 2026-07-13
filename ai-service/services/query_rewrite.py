"""查询改写与扩展（对齐 WeKnora KnowledgeQA 的 query rewrite + query expansion）。

仅用于普通问答（rag 模式 retrieve(enhance=True)）：
- ``rewrite_query``：把口语化 / 长问句改写成检索友好的关键词短句，提升措辞不一致时的召回。
- ``expand_query``：基于原问题生成 1~2 个语义不同角度的检索 query，用于主检索召回不足时兜底。

失败策略：
- 模型配置错误（:class:`ModelConfigError`）向上抛出，由路由转 ``MODEL_CONFIG_ERROR``
  （rewrite 在主检索前，配置错误应透传让用户重配）；
- 其他异常降级（rewrite 返回原话、expand 返回空列表），不阻断主流程。
"""

from loguru import logger

from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError

_REWRITE_SYSTEM = """你是企业知识库的检索优化助手。
请把用户的自然语言问题改写成适合向量检索与关键词检索的简短查询。
要求：
- 保留核心实体、动作、领域术语
- 拆分为关键词或短语，去除口语化冗余（如"请问""我想知道"等语气词可删）
- 直接输出改写后的查询，不要解释，不要加引号
- 若原问题已是简洁检索词，可基本原样返回"""

_EXPAND_SYSTEM = """你是企业知识库的检索优化助手。
请基于用户问题，生成 2 个从不同角度 / 不同表述切入的检索查询，以补充主检索召回不足的情况。
要求：
- 每个查询聚焦问题的一个侧面，用词与原问题有差异（换同义词、不同粒度）
- 每行一个查询，共 2 行
- 不要编号，不要解释，不要加引号"""


async def rewrite_query(
    question: str,
    cfg: ModelConfig | None = None,
    client=None,
) -> str:
    """把用户问题改写为检索友好 query（增强召回鲁棒性）。

    Args:
        question: 用户原始问题
        cfg: 运行时模型配置；为空用 env 兜底
        client: 可选 httpx 客户端（测试注入 mock）

    Returns:
        改写后的查询；改写失败（非配置错误）时降级返回原问题。

    Raises:
        ModelConfigError: LLM 配置错误（无 Key / Key 错 / 模型名错）时上抛。
    """
    messages = [
        {"role": "system", "content": _REWRITE_SYSTEM},
        {"role": "user", "content": question},
    ]
    try:
        out = await llm_service.complete(messages, cfg=cfg, client=client)
    except ModelConfigError:
        raise
    except Exception as e:
        logger.warning(f"query rewrite 失败，降级用原话检索: {e}")
        return question
    out = (out or "").strip()
    return out or question


async def expand_query(
    question: str,
    cfg: ModelConfig | None = None,
    client=None,
) -> list[str]:
    """基于原问题生成扩展检索 query 列表（主检索召回不足时兜底）。

    Args:
        question: 用户原始问题
        cfg: 运行时模型配置；为空用 env 兜底
        client: 可选 httpx 客户端（测试注入 mock）

    Returns:
        扩展查询字符串列表（最多 2 个）；任何异常时返回空列表（兜底不阻断主流程）。
    """
    messages = [
        {"role": "system", "content": _EXPAND_SYSTEM},
        {"role": "user", "content": question},
    ]
    try:
        out = await llm_service.complete(messages, cfg=cfg, client=client)
    except Exception as e:
        logger.warning(f"query expansion 失败，不扩展: {e}")
        return []
    queries = [q.strip() for q in out.splitlines() if q.strip()]
    return queries[:2]
