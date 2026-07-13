"""Agent 服务 — 自研轻量 ReAct 多步推理（M4-1）。

基于现有 LLM 流式补全（services.llm.stream_messages）实现 Think→Act→Observe 循环：
- Think：LLM 输出 ReAct 格式（Thought / Action / Action Input / Final Answer）
- Act：解析 Action 与参数，调用已注册工具
- Observe：工具结果作为 Observation 回填，进入下一轮
最终解析出 Final Answer 流式推送（token 事件），并汇总检索来源（sources 事件）。

工具范围（M4-1）：仅 knowledge_base_search（包 rag_service.retrieve）。
联网搜索工具（web_search）留待 M4-3，届时仅需新增一个工具注册 + 开关，架构零改动
（与 WeKnora 的「注册表 + 白名单」思路一致）。
"""

import json
import re
from dataclasses import asdict, dataclass
from typing import AsyncGenerator

from loguru import logger

from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError
from services.rag import rag_service

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
当用户问题需要查找资料时，你可以使用工具逐步检索知识库，再综合给出答案。

请严格按以下格式推理（每次只输出一轮）：
Thought: 你的思考过程
Action: knowledge_base_search
Action Input: {"query": "要检索的问题"}
...（工具返回 Observation 后，继续下一轮 Thought/Action，或给出最终答案）

当你已掌握足够信息，输出：
Thought: 总结思考
Final Answer: 给用户的完整回答

注意：
- 只能使用 knowledge_base_search 这一个工具
- Action Input 必须是 JSON，包含 query 字段
- 不要编造知识库中不存在的内容
- 若知识库检索明确返回「未找到相关内容」，请如实告知用户知识库中无相关信息，不得编造或使用通用知识虚构
- Final Answer 直接面向用户，简洁准确、使用中文
"""


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


def _build_messages(question: str, history: list[dict] | None) -> list[dict]:
    messages: list[dict] = [{"role": "system", "content": _SYSTEM_PROMPT}]
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


async def _execute_tool(
    tool_name: str,
    tool_input: str,
    kb_ids: list[str],
    tenant_id: str,
    cfg: ModelConfig | None,
) -> tuple[str, list[dict], bool]:
    """执行工具，返回 (observation 文本, 来源列表, 是否模型配置错误)。

    M4-1 仅支持 knowledge_base_search；未知工具返回可恢复的观察（让模型换思路）。
    """
    if tool_name == "knowledge_base_search":
        query = _extract_query(tool_input)
        logger.info(f"[Agent诊断] 原始ActionInput={tool_input!r} 清洗后query={query!r}")
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

    return f"未知工具：{tool_name}（当前仅支持 knowledge_base_search）。", [], False


async def run_agent(
    question: str,
    kb_ids: list[str],
    tenant_id: str,
    model: str | None,
    history: list[dict] | None,
    cfg: ModelConfig | None,
) -> AsyncGenerator[dict, None]:
    """执行 ReAct Agent，逐事件 yield 字典（由路由封装为 SSE）。

    Yields 事件字典：
      - agent_step: {"step", "type": "thought"|"action"|"observation", "content"/"tool"/"input"/"success"}
      - sources: {"sources": [...]}
      - token: {"content": "..."}
      - error: {"error_type": "MODEL_CONFIG_ERROR"|"AGENT_ERROR", "message": "..."}
    """
    sources: list[dict] = []
    messages = _build_messages(question, history)

    for i in range(MAX_AGENT_ITERATIONS):
        try:
            full = ""
            async for tok in llm_service.stream_messages(
                messages, model=model, cfg=cfg
            ):
                full += tok
        except ModelConfigError as e:
            logger.warning(f"Agent LLM 阶段模型配置错误: {e}")
            yield {
                "event": "error",
                "data": {"error_type": "MODEL_CONFIG_ERROR", "message": str(e)},
            }
            return

        step = parse_react(full)
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

        # Act：执行工具
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

        # Observe：回填 Observation，进入下一轮
        messages.append({"role": "assistant", "content": full})
        messages.append({"role": "user", "content": f"Observation: {observation}"})

    # 超出最大轮数：兜底提示
    logger.warning("Agent 达到最大推理轮数仍未产出最终答案")
    yield {
        "event": "token",
        "data": {
            "content": "抱歉，我未能在限定步骤内得出完整结论，请尝试拆分问题或补充更多信息。"
        },
    }
