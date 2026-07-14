"""Agent 智能增强 — memory 固化 + reflection 反思 + 上下文压缩（M4-C 轻量增强）。

借鉴 EventAgentReflection 的反思循环与 memoryConsolidator 的压缩思路：
- memory 固化：从多轮对话历史提取关键事实，压缩为记忆块注入 system prompt
- reflection：每轮工具观察后快速 LLM 自评（异步非阻塞），判断信息是否足够回答
- 上下文压缩：messages 超长时压缩旧轮次观察，保留最近 N 条原貌
"""

import json

from loguru import logger

from core.config import settings
from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError


# ═══════════════════════════════════════════════════════════════════════
# Memory 固化 — 从多轮历史提取关键事实压缩为记忆块
# ═══════════════════════════════════════════════════════════════════════

_MEMORY_PROMPT = (
    "从以下对话历史中提取关键事实与用户意图，整理为简短的记忆摘要（3~5 条要点，每条一句话）。"
    "仅提取客观事实，不推测、不编造。直接输出要点列表。"
)


async def consolidate_memory(
    history: list[dict],
    question: str,
    cfg: ModelConfig | None,
) -> str:
    """从多轮对话历史提取关键事实，压缩为记忆块供 Agent 后续推理使用。

    通过一次轻量 LLM 调用，从 history 中提取实体、用户偏好、已确认结论等，
    使 Agent 在长对话中不丢失上文信息。

    Args:
        history: 对话历史 [{"role":"user"/"assistant", "content":"..."}]
        question: 当前用户问题
        cfg: 运行时模型配置

    Returns:
        记忆块文本（可直接注入 system prompt）；失败或历史不足时返回空字符串
    """
    if not history or len(history) < settings.agent_memory_min_messages:
        return ""

    history_text = "\n".join(
        f"{'用户' if h['role'] == 'user' else '助手'}: {h.get('content', '')}"
        for h in history[-40:]  # 最多取最近 40 条
    )

    messages = [
        {"role": "system", "content": _MEMORY_PROMPT},
        {
            "role": "user",
            "content": (
                f"对话历史:\n{history_text}\n\n"
                f"当前问题: {question}\n\n提取关键记忆："
            ),
        },
    ]

    try:
        result = await llm_service.complete(messages, cfg=cfg)
        return result.strip()
    except ModelConfigError:
        raise
    except Exception as e:
        logger.warning(f"memory consolidation 失败: {e}")
        return ""


# ═══════════════════════════════════════════════════════════════════════
# Reflection 反思 — 工具观察后 LLM 自评信息充分性
# ═══════════════════════════════════════════════════════════════════════

_REFLECT_PROMPT = (
    "你是 Agent 反思器。根据检索结果判断当前信息是否足以直接回答用户问题。"
    "仅输出 JSON：{\"can_answer\": true/false, \"reason\": \"简短判断理由\"}\n\n"
    "判断标准：\n"
    "- 检索结果明确回答了用户问题 → can_answer=true\n"
    "- 检索结果为空或不相关 → can_answer=false\n"
)


async def reflect(
    question: str,
    observation: str,
    iteration: int,
    cfg: ModelConfig | None,
) -> dict:
    """工具观察后快速反思，判断信息是否足够回答。

    借鉴 EventAgentReflection：每轮工具调用后 LLM 自评检索质量，
    避免无效多轮或过早中断。仅输出结构化结果，不修改 messages。

    Args:
        question: 用户原始问题
        observation: 本轮工具观察结果文本
        iteration: 当前迭代轮数（0-based）
        cfg: 运行时模型配置

    Returns:
        {"can_answer": bool, "reason": str}
    """
    messages = [
        {"role": "system", "content": _REFLECT_PROMPT},
        {
            "role": "user",
            "content": f"用户问题: {question}\n\n"
                       f"检索结果:\n{observation}\n\n判断：",
        },
    ]
    try:
        text = await llm_service.complete(messages, cfg=cfg)
        text = text.strip()
        # 兼容 LLM 输出可能混入解释文字：提取首个 {...} 对象
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            obj = json.loads(text[start : end + 1])
        else:
            obj = json.loads(text)
        return {
            "can_answer": bool(obj.get("can_answer", True)),
            "reason": obj.get("reason", ""),
        }
    except ModelConfigError:
        raise
    except (json.JSONDecodeError, KeyError) as e:
        logger.warning(f"reflection 解析失败（默认继续检索）: {e}")
        return {"can_answer": False, "reason": "解析失败"}


# ═══════════════════════════════════════════════════════════════════════
# 上下文窗口压缩 — messages 超长时压缩旧轮次
# ═══════════════════════════════════════════════════════════════════════

_COMPRESS_PROMPT = (
    "你是上下文压缩器。将以下助手与工具观察消息压缩为一条简短摘要（1~2 句话），"
    "保留检索到的关键事实和结论，丢弃冗余描述。仅输出摘要文本。"
)


def estimate_chars(messages: list[dict]) -> int:
    """粗略估算 messages 总字符数（用作压缩判断）。"""
    return sum(
        len(m.get("content") or "")
        + len(m.get("tool_calls") or "")
        for m in messages
    )


async def compress_context(
    messages: list[dict],
    cfg: ModelConfig | None,
    *,
    keep_recent: int = 6,
) -> list[dict]:
    """压缩旧轮次消息，保留最近 N 条消息原貌。

    借鉴 memoryConsolidator：对早期的 assistant + tool 消息做摘要压缩，
    保留 system / user 问题与最近 assistant/tool 消息不变。

    Args:
        messages: Agent 累积消息列表
        cfg: 运行时模型配置
        keep_recent: 保留最近 N 条消息不压缩（默认 6）

    Returns:
        压缩后的消息列表；若无需压缩则返回原列表
    """
    if len(messages) <= keep_recent + 2:
        return messages

    # 分区：system 消息 + 旧轮次 + 近期轮次
    system_msgs = [m for m in messages if m["role"] == "system"]
    recent_msgs = messages[-keep_recent:]
    old_msgs = messages[len(system_msgs) : -keep_recent]

    # 收集旧轮次中值得压缩的 assistant/tool 消息
    compressible = [
        m for m in old_msgs if m["role"] in ("assistant", "tool")
    ]
    if not compressible:
        return messages

    text = "\n".join(
        f"[{m['role']}]: {m.get('content', '')}" for m in compressible
    )
    if len(text) < 300:
        return messages  # 太少不值得压缩

    compress_msgs = [
        {"role": "system", "content": _COMPRESS_PROMPT},
        {"role": "user", "content": f"消息:\n{text[:4000]}\n\n压缩为简短摘要："},
    ]
    try:
        summary = await llm_service.complete(compress_msgs, cfg=cfg)
        summary = summary.strip()
    except ModelConfigError:
        raise
    except Exception as e:
        logger.warning(f"context compression 失败: {e}")
        return messages

    # 重建消息列表
    result = list(system_msgs)
    # 保留旧轮次的 user 问题原文
    old_users = [m for m in old_msgs if m["role"] == "user"]
    result.extend(old_users)
    if summary:
        result.append(
            {"role": "system", "content": f"[历史摘要] {summary}"}
        )
    result.extend(recent_msgs)

    old_chars = estimate_chars(messages)
    new_chars = estimate_chars(result)
    logger.info(
        f"上下文压缩: {len(messages)} → {len(result)} 条消息, "
        f"{old_chars} → {new_chars} chars "
        f"（节省 {100 - new_chars * 100 // max(old_chars, 1)}%）"
    )
    return result
