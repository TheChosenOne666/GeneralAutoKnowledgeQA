"""Agent 服务单元测试（M4-B function calling）。

用桩替换 LLM（stream_agent_turn）与检索（rag_service.retrieve），聚焦：
- function calling 主路径：tool_calls → 执行工具 → 回填 → 多轮 → 最终答案流式
- ReAct 文本降级路径：模型不支持 tools 时 parse_react 仍驱动多步推理
- 工具参数解析（_resolve_query，兼容 function calling JSON 与 ReAct 文本）
- 事件协议（agent_step / token / sources / error）

不依赖真实模型 Key。
"""

import asyncio
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from main import app
from services.agent import (
    _execute_tool,
    _extract_query,
    _resolve_query,
    parse_react,
    run_agent,
)
from services.model_config import ModelConfig, ModelConfigError
from services.vector_store import RetrievalResult


class _FakeAgentTurn:
    """按调用顺序返回脚本化事件流（token / tool_calls），模拟 stream_agent_turn。"""

    def __init__(self, turns: list[list[dict]]):
        self._turns = list(turns)
        self._i = 0
        self.calls = 0

    async def stream_agent_turn(self, messages, tools, model=None, cfg=None, client=None):
        idx = min(self._i, len(self._turns) - 1)
        self._i += 1
        self.calls += 1
        for ev in self._turns[idx]:
            yield ev


async def _fake_retrieve(query, kb_ids, tenant_id, top_n=5, cfg=None):
    return [
        RetrievalResult(
            content="熊答是一款企业知识问答助手，支持文档向量化与检索。",
            source="doc.txt",
            page=1,
            score=0.9,
            doc_id="d1",
            kb_id="kb1",
            chunk_index=0,
        )
    ]


async def _fake_retrieve_empty(query, kb_ids, tenant_id, top_n=5, cfg=None):
    return []


async def _collect(events_gen) -> list[dict]:
    out = []
    async for e in events_gen:
        out.append(e)
    return out


class ToolEmptyKbTest(unittest.TestCase):
    def test_execute_tool_empty_kb_returns_no_content_constraint(self):
        """检索工具空结果：返回『未找到相关内容』并约束不编造。"""
        with patch("services.agent.rag_service.retrieve", _fake_retrieve_empty):
            observation, sources, is_config_error = asyncio.run(
                _execute_tool(
                    "knowledge_base_search",
                    '{"query": "知识库里没有的话题"}',
                    ["kb1", "kb2"],
                    "t1",
                    ModelConfig(),
                )
            )
        self.assertEqual(sources, [])
        self.assertFalse(is_config_error)
        self.assertIn("未找到相关内容", observation)
        self.assertIn("严禁编造", observation)
        self.assertIn("2 个知识库", observation)

    def test_resolve_query_from_function_calling_json(self):
        self.assertEqual(
            _resolve_query('{"query": "算法入职前期准备"}'), "算法入职前期准备"
        )

    def test_resolve_query_from_react_fenced_text(self):
        # 降级：ReAct 文本（含 ```json 围栏）也应能解析出 query
        self.assertEqual(
            _resolve_query('```json\n{"query": "算法入职前期准备"}\n```'),
            "算法入职前期准备",
        )

    def test_resolve_query_empty(self):
        self.assertEqual(_resolve_query(""), "")
        self.assertEqual(_resolve_query("   "), "")


class FunctionCallingTest(unittest.TestCase):
    def test_multi_round_emits_steps_and_sources(self):
        fake = _FakeAgentTurn(
            [
                [
                    {"type": "token", "content": "需要检索知识库"},
                    {
                        "type": "tool_calls",
                        "calls": [
                            {
                                "id": "call_1",
                                "name": "knowledge_base_search",
                                "arguments": '{"query": "熊答是什么"}',
                            }
                        ],
                    },
                ],
                [{"type": "token", "content": "熊答是企业知识问答助手。"}],
            ]
        )
        with patch(
            "services.agent.llm_service.stream_agent_turn", fake.stream_agent_turn
        ), patch("services.agent.rag_service.retrieve", _fake_retrieve):
            events = asyncio.run(
                _collect(
                    run_agent(
                        question="熊答是什么",
                        kb_ids=["kb1"],
                        tenant_id="t1",
                        model=None,
                        history=[],
                        cfg=ModelConfig(),
                    )
                )
            )

        types = [e["event"] for e in events]
        self.assertIn("agent_step", types)
        self.assertIn("sources", types)
        self.assertIn("token", types)

        steps = [e for e in events if e["event"] == "agent_step"]
        self.assertEqual(steps[0]["data"]["type"], "thought")
        self.assertEqual(steps[0]["data"]["step"], 0)
        self.assertEqual(steps[1]["data"]["type"], "action")
        self.assertEqual(steps[1]["data"]["tool"], "knowledge_base_search")
        self.assertEqual(steps[2]["data"]["type"], "observation")
        self.assertTrue(steps[2]["data"]["success"])

        sources_evt = [e for e in events if e["event"] == "sources"][0]
        self.assertEqual(len(sources_evt["data"]["sources"]), 1)
        token_text = "".join(
            e["data"]["content"] for e in events if e["event"] == "token"
        )
        self.assertIn("企业知识问答助手", token_text)

    def test_single_round_final_no_action(self):
        fake = _FakeAgentTurn(
            [[{"type": "token", "content": "熊答是助手。"}]]
        )
        with patch(
            "services.agent.llm_service.stream_agent_turn", fake.stream_agent_turn
        ):
            events = asyncio.run(
                _collect(
                    run_agent(
                        question="你是谁",
                        kb_ids=[],
                        tenant_id="t1",
                        model=None,
                        history=[],
                        cfg=ModelConfig(),
                    )
                )
            )
        step_types = [
            s["data"].get("type")
            for s in events
            if s["event"] == "agent_step"
        ]
        self.assertNotIn("action", step_types)
        self.assertIn("token", [e["event"] for e in events])
        token_text = "".join(
            e["data"]["content"] for e in events if e["event"] == "token"
        )
        self.assertIn("熊答是助手", token_text)

    def test_config_error_yields_error_event(self):
        async def _boom(*args, **kwargs):
            raise ModelConfigError("Embedding Key 错误")

        fake = _FakeAgentTurn(
            [
                [
                    {"type": "token", "content": "需要检索"},
                    {
                        "type": "tool_calls",
                        "calls": [
                            {
                                "id": "call_1",
                                "name": "knowledge_base_search",
                                "arguments": '{"query": "x"}',
                            }
                        ],
                    },
                ],
            ]
        )
        with patch(
            "services.agent.llm_service.stream_agent_turn", fake.stream_agent_turn
        ), patch("services.agent.rag_service.retrieve", _boom):
            events = asyncio.run(
                _collect(
                    run_agent(
                        question="x",
                        kb_ids=["kb1"],
                        tenant_id="t1",
                        model=None,
                        history=[],
                        cfg=ModelConfig(),
                    )
                )
            )
        err = [e for e in events if e["event"] == "error"]
        self.assertTrue(err, "应产出 error 事件")
        self.assertEqual(err[0]["data"]["error_type"], "MODEL_CONFIG_ERROR")


class FallbackReactTest(unittest.TestCase):
    """模型不支持 function calling（返回纯 ReAct 文本，无 tool_calls）时降级仍工作。"""

    def test_react_text_fallback_multi_round(self):
        fake = _FakeAgentTurn(
            [
                [
                    {
                        "type": "token",
                        "content": 'Thought: 需要检索\nAction: knowledge_base_search\nAction Input: {"query": "熊答是什么"}',
                    }
                ],
                [
                    {
                        "type": "token",
                        "content": "Thought: 根据检索结果可以回答\nFinal Answer: 熊答是企业知识问答助手。",
                    }
                ],
            ]
        )
        with patch(
            "services.agent.llm_service.stream_agent_turn", fake.stream_agent_turn
        ), patch("services.agent.rag_service.retrieve", _fake_retrieve):
            events = asyncio.run(
                _collect(
                    run_agent(
                        question="熊答是什么",
                        kb_ids=["kb1"],
                        tenant_id="t1",
                        model=None,
                        history=[],
                        cfg=ModelConfig(),
                    )
                )
            )
        types = [e["event"] for e in events]
        self.assertIn("agent_step", types)
        self.assertIn("sources", types)
        self.assertIn("token", types)
        steps = [e for e in events if e["event"] == "agent_step"]
        self.assertEqual(steps[1]["data"]["type"], "action")
        self.assertEqual(steps[1]["data"]["tool"], "knowledge_base_search")
        token_text = "".join(
            e["data"]["content"] for e in events if e["event"] == "token"
        )
        self.assertIn("企业知识问答助手", token_text)

    def test_react_empty_kb_observation_flow(self):
        fake = _FakeAgentTurn(
            [
                [
                    {
                        "type": "token",
                        "content": 'Thought: 需要检索\nAction: knowledge_base_search\nAction Input: {"query": "x"}',
                    }
                ],
                [
                    {
                        "type": "token",
                        "content": "Thought: 检索为空\nFinal Answer: 知识库中未找到相关信息。",
                    }
                ],
            ]
        )
        with patch(
            "services.agent.llm_service.stream_agent_turn", fake.stream_agent_turn
        ), patch("services.agent.rag_service.retrieve", _fake_retrieve_empty):
            events = asyncio.run(
                _collect(
                    run_agent(
                        question="x",
                        kb_ids=["kb1"],
                        tenant_id="t1",
                        model=None,
                        history=[],
                        cfg=ModelConfig(),
                    )
                )
            )
        obs = [
            e for e in events
            if e["event"] == "agent_step" and e["data"]["type"] == "observation"
        ]
        self.assertTrue(obs, "应产出 observation 事件")
        self.assertIn("未找到相关内容", obs[0]["data"]["content"])


class ParseReactTest(unittest.TestCase):
    def test_parse_final_answer(self):
        s = parse_react("Thought: 我可以直接回答\nFinal Answer: 熊答是助手。")
        self.assertTrue(s.is_final)
        self.assertEqual(s.final_answer, "熊答是助手。")
        self.assertEqual(s.thought, "我可以直接回答")

    def test_parse_action(self):
        s = parse_react(
            'Thought: 需要检索\nAction: knowledge_base_search\nAction Input: {"query": "熊答是什么"}'
        )
        self.assertFalse(s.is_final)
        self.assertEqual(s.action, "knowledge_base_search")
        self.assertEqual(s.action_input, '{"query": "熊答是什么"}')

    def test_parse_plain_text_as_final(self):
        s = parse_react("熊答是一款企业知识问答助手。")
        self.assertTrue(s.is_final)
        self.assertIn("熊答", s.final_answer)


class ExtractQueryTest(unittest.TestCase):
    """_extract_query 容错：覆盖 LLM 常见输出形态（之前仅覆盖干净 JSON）。"""

    def test_clean_json(self):
        self.assertEqual(_extract_query('{"query": "算法入职前期准备"}'), "算法入职前期准备")

    def test_markdown_fenced_json(self):
        self.assertEqual(
            _extract_query('```json\n{"query": "算法入职前期准备"}\n```'),
            "算法入职前期准备",
        )

    def test_json_with_surrounding_text(self):
        s = '这是我的检索：\n{"query": "算法岗入职准备"}\n请检索'
        self.assertEqual(_extract_query(s), "算法岗入职准备")

    def test_first_json_object_when_trailing_text(self):
        s = '{"query": "算法入职准备"} 我需要相关知识'
        self.assertEqual(_extract_query(s), "算法入职准备")

    def test_plain_text_fallback(self):
        self.assertEqual(_extract_query("算法入职前期准备"), "算法入职前期准备")

    def test_empty_input(self):
        self.assertEqual(_extract_query(""), "")
        self.assertEqual(_extract_query("   "), "")


class AgentRouteTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_agent_mode_sse_function_calling(self):
        fake = _FakeAgentTurn(
            [
                [
                    {"type": "token", "content": "需要检索知识库"},
                    {
                        "type": "tool_calls",
                        "calls": [
                            {
                                "id": "call_1",
                                "name": "knowledge_base_search",
                                "arguments": '{"query": "熊答是什么"}',
                            }
                        ],
                    },
                ],
                [{"type": "token", "content": "熊答是企业知识问答助手。"}],
            ]
        )
        with patch(
            "services.agent.llm_service.stream_agent_turn", fake.stream_agent_turn
        ), patch("services.agent.rag_service.retrieve", _fake_retrieve):
            with self.client.stream(
                "POST",
                "/ai/chat/stream",
                json={
                    "question": "熊答是什么",
                    "kb_ids": ["kb1"],
                    "tenant_id": "t1",
                    "mode": "agent",
                },
            ) as resp:
                body = "".join(resp.iter_text())

        self.assertIn("event: agent_step", body)
        self.assertIn('"type": "thought"', body)
        self.assertIn('"type": "action"', body)
        self.assertIn('"type": "observation"', body)
        self.assertIn("event: sources", body)
        self.assertIn("event: token", body)
        self.assertIn("event: done", body)
        self.assertIn("knowledge_base_search", body)


if __name__ == "__main__":
    unittest.main()
