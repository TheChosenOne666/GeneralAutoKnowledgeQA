"""Agent 服务 — 多步推理（M4-B 升级为原生 function calling）。

M4-1 为自研 ReAct 文本协议（Think→Act→Observe），靠正则解析 LLM 输出的
Thought/Action/Action Input，脆弱易错（M4-1 修复 `_extract_query` 即为佐证）。
M4-B 升级为 OpenAI 兼容的原生 function calling：LLM 直接返回结构化 tool_calls，
解析可靠，彻底去掉对文本 Action 解析的依赖。

为兼容「用户配置了不支持 function calling 的模型」这一场景，保留 ReAct 文本协议
作为降级：当本轮 LLM 未返回 tool_calls（仅返回文本）时，仍用 parse_react /
_extract_query 解析 Action / Final Answer。两条路径共用同一套事件协议
（agent_step / sources / token / error），前端与 Java 无需改动。

工具范围：仅 knowledge_base_search（包 rag_service.retrieve）。联网搜索工具
（web_search）留待 M4-3，届时仅需新增 TOOLS 中一个工具定义 + 开关，架构零改动
（与 WeKnora 的「注册表 + 白名单」思路一致）。
"""

import json
import re
from dataclasses import asdict, dataclass
from typing import AsyncGenerator

from loguru import logger

from core.config import settings
from services.agent_intelligence import (
    compress_context,
    consolidate_memory,
    estimate_chars,
    reflect,
)
from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError
from services.rag import rag_service
from services.web_search import web_search, format_search_results

MAX_AGENT_ITERATIONS = 5

# ReAct 解析正则（大小写不敏感，兼容中英文冒号）
_RE_FINAL = re.compile(r"Final\s*Answer:\s*(.*)", re.S | re.I)
_RE_ACTION = re.compile(r"Action:\s*([^\n]+)", re.I)
_RE_ACTION_INPUT = re.compile(
    r"Action\s*Input:\s*(.*?)(?=\n\s*(Thought|Observation|Final Answer|Action)\s*:|$)",
    re.S | re.I,
)
_RE_THOUGHT = re.compile(
    r"Thought:\s*(.*?)(?=\n\s*(Action|Final Answer)\s*:|$)", re.S | re.I
)

_SYSTEM_PROMPT = """你是熊答，一个企业知识问答助手，具备多步推理能力。

当需要查找企业资料、确认事实或获取文档内容时，请调用 knowledge_base_search 工具检索知识库。
当知识库无相关内容、或用户询问时效性/外部信息时，请调用 web_search 联网搜索。
综合检索结果给出准确回答。工具参数 query 应简洁明确，表达用户真正想了解的信息。

回答要求：
- 优先使用知识库信息，知识库无结果时再用联网搜索
- 不要编造不存在的内容
- 若所有检索均未找到相关内容，请如实告知用户，不得编造或使用通用知识虚构
- 最终回答直接面向用户，简洁准确、使用中文
- 仅在信息已充分时给出最终答案；若需更多资料，继续调用对应工具检索
- 联网搜索结果应注明来源 URL

（降级说明：若当前环境无法调用工具，请用纯文本按以下格式逐轮回答：
Thought: 你的思考
Action: knowledge_base_search
Action Input: {"query": "要检索的问题"}
或最终：Final Answer: 给用户的完整回答）
"""

# OpenAI 兼容 function-calling 工具定义（M4-B）。
# M4-3 新增 web_search：当知识库无相关内容或需要实时信息时，联网搜索。
TOOLS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "knowledge_base_search",
            "description": "检索企业知识库以回答用户问题。当需要查找资料、确认事实、获取文档内容时使用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "检索查询语句，应简洁明确，表达用户真正想了解的信息。",
                    }
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": (
                "联网搜索以获取实时信息或知识库中没有的内容。"
                "当知识库检索无结果、或用户需要最新资讯时使用此工具。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "搜索关键词，简洁明确。",
                    }
                },
                "required": ["query"],
            },
        },
    },
]


@dataclass
class ReactStep:
    """单轮 ReAct 解析结果。"""

    thought: str
    is_final: bool
    final_answer: str = ""
    action: str = ""
    action_input: str = ""


def parse_react(text: str) -> ReactStep:
    """解析 LLM 输出的 ReAct 文本。

    优先级：存在 Final Answer 且其后无 Action → 视为最终答案；
    否则有 Action → 视为工具调用；否则整段作为最终答案兜底。
    """
    text = (text or "").strip()

    final_match = _RE_FINAL.search(text)
    if final_match:
        after = text[final_match.end():]
        if not _RE_ACTION.search(after):
            return ReactStep(
                thought=_extract_thought(text, final_match.start()),
                is_final=True,
                final_answer=final_match.group(1).strip(),
            )

    action_match = _RE_ACTION.search(text)
    if action_match:
        inp = ""
        inp_match = _RE_ACTION_INPUT.search(text)
        if inp_match:
            inp = inp_match.group(1).strip()
        return ReactStep(
            thought=_extract_thought(text, action_match.start()),
            is_final=False,
            action=action_match.group(1).strip(),
            action_input=inp,
        )

    # 无明确结构 → 整段作为最终答案
    return ReactStep(thought=text, is_final=True, final_answer=text)


def _extract_thought(text: str, end_pos: int) -> str:
    m = _RE_THOUGHT.search(text)
    if m:
        return m.group(1).strip()
    return text[:end_pos].strip()


def _extract_query(tool_input: str) -> str:
    """从 Action Input 提取检索 query。

    兼容 LLM 常见输出形态：标准 JSON（{"query": ...}）、被 markdown 代码块
    （```json ... ```）包裹的 JSON、前后混入解释文字，以及纯文本。

    旧实现一旦 json.loads 失败就把整段脏字符串（含 ``` 围栏）当 query 去检索，
    导致 Agent 检索必然落空；这里改为先剥离围栏、再尝试提取首个 JSON 对象，
    使 LLM 用代码块包裹 Action Input 时也能正确取出 query。
    """
    s = (tool_input or "").strip()
    if not s:
        return ""

    # 1) 去除 markdown 代码块围栏（``` 或 ```json）
    fenced = re.match(r"^```(?:json)?\s*(.*?)\s*```$", s, re.S | re.I)
    if fenced:
        s = fenced.group(1).strip()

    # 2) 尝试直接解析标准 JSON
    try:
        obj = json.loads(s)
    except json.JSONDecodeError:
        # 3) 退化：从文本中提取第一个 {...} 对象再解析
        obj_match = re.search(r"\{[^{}]*\}", s, re.S)
        if obj_match:
            try:
                obj = json.loads(obj_match.group(0))
            except json.JSONDecodeError:
                obj = None
        else:
            obj = None

    if isinstance(obj, dict):
        return (obj.get("query") or obj.get("q") or "").strip()
    if isinstance(obj, str):
        return obj.strip()
    # 4) 非 JSON：把清洗后的文本作为纯查询
    return s


def _build_messages(
    question: str,
    history: list[dict] | None,
    memory_block: str = "",
) -> list[dict]:
    messages: list[dict] = [{"role": "system", "content": _SYSTEM_PROMPT}]
    if memory_block:
        messages.append(
            {"role": "system", "content": f"[对话记忆]\n{memory_block}"}
        )
    for h in history or []:
        role = h.get("role")
        if role in ("user", "assistant"):
            messages.append({"role": role, "content": h.get("content", "")})
    messages.append({"role": "user", "content": f"问题: {question}"})
    return messages


def _chunk(text: str, size: int = 8):
    """把最终答案切成小块模拟流式 token。"""
    text = text.strip()
    if not text:
        return
    for i in range(0, len(text), size):
        yield text[i : i + size]


def _resolve_query(arguments: str) -> str:
    """从工具参数解析检索 query（兼容 function calling JSON 与 ReAct 降级文本）。

    - function calling：LLM 返回标准 JSON 字符串 ``{"query": "..."}`` → json.loads 取 query
    - ReAct 降级：LLM 输出的 Action Input（可能含 ```json 围栏 / 多余文字）→ 退化为
      :func:`_extract_query` 容错解析
    """
    s = (arguments or "").strip()
    if not s:
        return ""
    try:
        obj = json.loads(s)
    except json.JSONDecodeError:
        return _extract_query(s)
    if isinstance(obj, dict):
        return (obj.get("query") or obj.get("q") or "").strip()
    if isinstance(obj, str):
        return obj.strip()
    return ""


async def _execute_tool(
    tool_name: str,
    arguments: str,
    kb_ids: list[str],
    tenant_id: str,
    cfg: ModelConfig | None,
) -> tuple[str, list[dict], bool]:
    """执行工具，返回 (observation 文本, 来源列表, 是否模型配置错误)。

    arguments 兼容两种来源：function calling 的标准 JSON 字符串，或 ReAct 降级的
    Action Input 文本（含围栏 / 多余文字），统一由 :func:`_resolve_query` 解析。
    支持 knowledge_base_search（M4-1）和 web_search（M4-3）。未知工具返回可恢复的观察。
    """
    if tool_name == "knowledge_base_search":
        query = _resolve_query(arguments)
        logger.info(f"[Agent诊断] 工具={tool_name} 解析query={query!r}")
        if not query:
            return "工具参数错误：未提供有效查询。", [], False
        try:
            results = await rag_service.retrieve(
                query, kb_ids, tenant_id, top_n=5, cfg=cfg
            )
        except ModelConfigError as e:
            logger.warning(f"Agent 检索工具模型配置错误: {e}")
            return f"工具执行失败（模型配置错误）：{e}", [], True

        if not results:
            kb_count = len(kb_ids)
            observation = (
                f"在知识库中未找到相关内容（已检索 {kb_count} 个知识库）。\n"
                "- 不要使用训练数据或通用知识编造答案\n"
                "- 直接说明「知识库中未找到相关信息」\n"
                "- 严禁编造或虚构来源"
            )
            return observation, [], False

        lines: list[str] = []
        sources: list[dict] = []
        for idx, r in enumerate(results, 1):
            snippet = r.content[:300]
            lines.append(f"[{idx}] 来源：《{r.source}》第{r.page}页 — {snippet}")
            sources.append(asdict(r))
        observation = f"检索到 {len(results)} 条相关内容：\n" + "\n".join(lines)
        return observation, sources, False

    # M4-3：联网搜索工具
    if tool_name == "web_search":
        query = _resolve_query(arguments)
        logger.info(f"[Agent诊断] 工具={tool_name} 解析query={query!r}")
        if not query:
            return "工具参数错误：未提供有效查询。", [], False
        try:
            results = await web_search(
                query, max_results=settings.web_search_max_results
            )
        except Exception as e:
            logger.warning(f"Agent 联网搜索失败: {e}")
            return f"联网搜索失败：{e}。请尝试其他检索方式。", [], False

        if not results:
            return (
                "联网搜索未找到相关内容。"
                "请勿编造，直接说明「未搜索到相关信息」。"
            ), [], False

        observation = format_search_results(results)
        web_sources: list[dict] = []
        for r in results:
            web_sources.append({
                "source": r.get("title", "网络来源") or "网络来源",
                "page": 0,
                "content": r.get("snippet", "")[:300],
                "score": 0.0,
                "doc_id": r.get("url", ""),
                "kb_id": "web",
            })
        return observation, web_sources, False

    return (
        f"未知工具：{tool_name}（当前支持 knowledge_base_search 和 web_search）。"
    ), [], False


async def run_agent(
    question: str,
    kb_ids: list[str],
    tenant_id: str,
    model: str | None,
    history: list[dict] | None,
    cfg: ModelConfig | None,
) -> AsyncGenerator[dict, None]:
    """执行 Agent 多步推理，逐事件 yield 字典（由路由封装为 SSE）。

    M4-B：优先走原生 function calling 主路径（LLM 返回 tool_calls 即调用工具并回填
    function-calling 协议消息）；若本轮 LLM 未返回 tool_calls（模型不支持 function
    calling），降级走 ReAct 文本解析（parse_react）。两条路径共用同一事件协议：
      - agent_step: {"step", "type": "thought"|"action"|"observation", "content"/"tool"/"input"/"success"}
      - sources: {"sources": [...]}
      - token: {"content": "..."}
      - error: {"error_type": "MODEL_CONFIG_ERROR"|"AGENT_ERROR", "message": "..."}

    M4-C：集成三项轻量增强（各自由 config 开关控制）：
      - memory 固化：长对话时从 history 提取关键事实为记忆块
      - reflection 反思：每轮工具观察后 LLM 自评信息充分性，足够时引导产出最终答案
      - 上下文压缩：messages 超长时压缩旧轮次观察，防超出 LLM 上下文窗口
    """
    sources: list[dict] = []

    # ── C1: Memory 固化 ──
    memory_block = ""
    if settings.enable_agent_memory:
        try:
            memory_block = await consolidate_memory(
                history or [], question, cfg
            )
        except ModelConfigError as e:
            logger.warning(f"memory 固化阶段模型配置错误: {e}")
            yield {
                "event": "error",
                "data": {"error_type": "MODEL_CONFIG_ERROR", "message": str(e)},
            }
            return
        except Exception as e:
            logger.warning(f"memory 固化失败（降级继续）: {e}")

    messages = _build_messages(question, history, memory_block=memory_block)

    for i in range(MAX_AGENT_ITERATIONS):
        # ── C3: 上下文压缩（每轮 LLM 调用前检查） ──
        if settings.enable_agent_compression:
            try:
                if estimate_chars(messages) > settings.agent_context_max_chars:
                    messages = await compress_context(messages, cfg)
            except ModelConfigError as e:
                logger.warning(f"上下文压缩阶段模型配置错误: {e}")
                yield {
                    "event": "error",
                    "data": {"error_type": "MODEL_CONFIG_ERROR", "message": str(e)},
                }
                return
            except Exception as e:
                logger.warning(f"上下文压缩失败（降级继续）: {e}")

        # 单轮流式生成：收集思考文本与（若有）工具调用
        thought_text = ""
        tool_calls: list[dict] = []
        try:
            async for ev in llm_service.stream_agent_turn(
                messages, TOOLS, model=model, cfg=cfg
            ):
                if ev["type"] == "token":
                    thought_text += ev["content"]
                elif ev["type"] == "tool_calls":
                    tool_calls = ev["calls"]
        except ModelConfigError as e:
            logger.warning(f"Agent LLM 阶段模型配置错误: {e}")
            yield {
                "event": "error",
                "data": {"error_type": "MODEL_CONFIG_ERROR", "message": str(e)},
            }
            return

        # ── 主路径：原生 function calling ──
        if tool_calls:
            if thought_text.strip():
                yield {
                    "event": "agent_step",
                    "data": {
                        "step": i,
                        "type": "thought",
                        "content": thought_text.strip(),
                    },
                }
            reached_config_error = False
            last_observation = ""
            for call in tool_calls:
                name = call.get("name", "")
                args = call.get("arguments", "")
                call_id = call.get("id") or f"call_{i}"
                yield {
                    "event": "agent_step",
                    "data": {
                        "step": i,
                        "type": "action",
                        "tool": name,
                        "input": args,
                    },
                }
                observation, new_sources, is_config_error = await _execute_tool(
                    name, args, kb_ids, tenant_id, cfg
                )
                last_observation = observation
                yield {
                    "event": "agent_step",
                    "data": {
                        "step": i,
                        "type": "observation",
                        "content": observation,
                        "success": not is_config_error,
                    },
                }
                if is_config_error:
                    reached_config_error = True
                    break
                if new_sources:
                    sources.extend(new_sources)
                # 回填 function calling 协议
                messages.append(
                    {
                        "role": "assistant",
                        "content": thought_text.strip() or None,
                        "tool_calls": [
                            {
                                "id": call_id,
                                "type": "function",
                                "function": {"name": name, "arguments": args},
                            }
                        ],
                    }
                )
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call_id,
                        "content": observation,
                    }
                )
            if reached_config_error:
                yield {
                    "event": "error",
                    "data": {
                        "error_type": "MODEL_CONFIG_ERROR",
                        "message": last_observation,
                    },
                }
                return

            # ── C2: Reflection 反思（工具观察后 LLM 自评） ──
            if settings.enable_agent_reflection and last_observation:
                try:
                    refl = await reflect(question, last_observation, i, cfg)
                    if refl.get("can_answer"):
                        logger.info(
                            f"Agent reflection: 信息已足够 → 引导产出最终答案 "
                            f"(reason={refl.get('reason')})"
                        )
                        messages.append(
                            {
                                "role": "system",
                                "content": (
                                    "检索信息已足够回答用户问题。"
                                    "请基于以上所有检索结果直接给出简洁准确的 Final Answer，"
                                    "不要再调用工具。"
                                ),
                            }
                        )
                except ModelConfigError as e:
                    logger.warning(f"reflection 阶段模型配置错误: {e}")
                    yield {
                        "event": "error",
                        "data": {
                            "error_type": "MODEL_CONFIG_ERROR",
                            "message": str(e),
                        },
                    }
                    return
                except Exception as e:
                    logger.warning(f"reflection 失败，降级继续: {e}")
            continue  # 工具已执行，进入下一轮推理

        # ── 降级路径：本轮无 tool_calls（模型不支持 function calling） ──
        step = parse_react(thought_text)
        if step.thought:
            yield {
                "event": "agent_step",
                "data": {"step": i, "type": "thought", "content": step.thought},
            }

        if step.is_final:
            answer = step.final_answer or "（未能生成答案）"
            if sources:
                yield {"event": "sources", "data": {"sources": sources}}
            for chunk in _chunk(answer):
                yield {"event": "token", "data": {"content": chunk}}
            return

        # Act（降级）：执行工具
        yield {
            "event": "agent_step",
            "data": {
                "step": i,
                "type": "action",
                "tool": step.action,
                "input": step.action_input,
            },
        }
        observation, new_sources, is_config_error = await _execute_tool(
            step.action, step.action_input, kb_ids, tenant_id, cfg
        )
        yield {
            "event": "agent_step",
            "data": {
                "step": i,
                "type": "observation",
                "content": observation,
                "success": not is_config_error,
            },
        }
        if is_config_error:
            yield {
                "event": "error",
                "data": {"error_type": "MODEL_CONFIG_ERROR", "message": observation},
            }
            return
        if new_sources:
            sources.extend(new_sources)

        # Observe（降级）：回填 Observation 文本，进入下一轮
        messages.append({"role": "assistant", "content": thought_text})
        messages.append({"role": "user", "content": f"Observation: {observation}"})

        # ── C2: Reflection（降级路径也做反思） ──
        if settings.enable_agent_reflection and observation:
            try:
                refl = await reflect(question, observation, i, cfg)
                if refl.get("can_answer"):
                    logger.info(
                        f"Agent reflection(降级): 信息已足够 → 引导产出最终答案"
                    )
                    messages.append(
                        {
                            "role": "system",
                            "content": "检索信息已足够，请直接给出 Final Answer，不要再调用工具。",
                        }
                    )
            except ModelConfigError as e:
                yield {
                    "event": "error",
                    "data": {
                        "error_type": "MODEL_CONFIG_ERROR",
                        "message": str(e),
                    },
                }
                return
            except Exception as e:
                logger.warning(f"reflection(降级) 失败: {e}")

    # 超出最大轮数：兜底提示
    logger.warning("Agent 达到最大推理轮数仍未产出最终答案")
    yield {
        "event": "token",
        "data": {
            "content": (
                "抱歉，我未能在限定步骤内得出完整结论，"
                "请尝试拆分问题或补充更多信息。"
            )
        },
    }
