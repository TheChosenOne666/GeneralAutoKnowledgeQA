"""Agent 服务单元测试（M4-1）。

用桩替换 LLM（stream_messages）与检索（rag_service.retrieve），聚焦 ReAct 循环、
工具解析、事件协议（agent_step / token / sources / error），不依赖真实模型 Key。
"""

import asyncio
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from main import app
from services.agent import parse_react, run_agent, _execute_tool
from services.model_config import ModelConfig
from services.vector_store import RetrievalResult


class _FakeLLM:
    """按调用顺序返回脚本化 ReAct 文本，逐块 yield 模拟流式。"""

    def __init__(self, responses: list[str]):
        self._responses = list(responses)
        self._i = 0
        self.calls = 0

    async def stream_messages(self, messages, model=None, cfg=None, client=None):
        idx = min(self._i, len(self._responses) - 1)
        self._i += 1
        self.calls += 1
        text = self._responses[idx]
        for i in range(0, len(text), 8):
            yield text[i : i + 8]


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


async def _collect(events_gen) -> list[dict]:
    out = []
    async for e in events_gen:
        out.append(e)
    return out


async def _fake_retrieve_empty(query, kb_ids, tenant_id, top_n=5, cfg=None):
    return []


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

    def test_agent_empty_kb_observation_flow(self):
        """端到端：Agent 检索空结果时，observation 事件携带不编造约束。"""
        fake_llm = _FakeLLM(
            [
                'Thought: 需要检索\nAction: knowledge_base_search\nAction Input: {"query": "x"}',
                "Thought: 检索为空\nFinal Answer: 知识库中未找到相关信息。",
            ]
        )
        with patch(
            "services.agent.llm_service.stream_messages", fake_llm.stream_messages
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


class AgentRunTest(unittest.TestCase):
    def test_multi_round_reasoning_emits_steps_and_sources(self):
        fake_llm = _FakeLLM(
            [
                'Thought: 需要检索知识库\nAction: knowledge_base_search\nAction Input: {"query": "熊答是什么"}',
                "Thought: 根据检索结果可以回答\nFinal Answer: 熊答是企业知识问答助手。",
            ]
        )
        with patch(
            "services.agent.llm_service.stream_messages", fake_llm.stream_messages
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

        # 验证步骤顺序：thought(0) → action(0) → observation(0) → thought(1) → sources → token
        steps = [e for e in events if e["event"] == "agent_step"]
        self.assertEqual(steps[0]["data"]["type"], "thought")
        self.assertEqual(steps[0]["data"]["step"], 0)
        self.assertEqual(steps[1]["data"]["type"], "action")
        self.assertEqual(steps[1]["data"]["tool"], "knowledge_base_search")
        self.assertEqual(steps[2]["data"]["type"], "observation")
        self.assertTrue(steps[2]["data"]["success"])

        # 检索来源被汇总
        sources_evt = [e for e in events if e["event"] == "sources"][0]
        self.assertEqual(len(sources_evt["data"]["sources"]), 1)
        # 最终答案以 token 流出
        token_text = "".join(
            e["data"]["content"] for e in events if e["event"] == "token"
        )
        self.assertIn("企业知识问答助手", token_text)

    def test_single_round_final_answer_no_action(self):
        fake_llm = _FakeLLM(["Thought: 直接回答\nFinal Answer: 熊答是助手。"])
        with patch(
            "services.agent.llm_service.stream_messages", fake_llm.stream_messages
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
        types = [e["event"] for e in events]
        self.assertNotIn(
            "action",
            [s["data"].get("type") for s in events if s["event"] == "agent_step"],
        )
        self.assertIn("token", types)
        token_text = "".join(
            e["data"]["content"] for e in events if e["event"] == "token"
        )
        self.assertIn("熊答是助手", token_text)

    def test_retrieve_config_error_yields_error_event(self):
        async def _boom(*args, **kwargs):
            from services.model_config import ModelConfigError

            raise ModelConfigError("Embedding Key 错误")

        fake_llm = _FakeLLM(
            [
                'Thought: 需要检索\nAction: knowledge_base_search\nAction Input: {"query": "x"}',
            ]
        )
        with patch(
            "services.agent.llm_service.stream_messages", fake_llm.stream_messages
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


class AgentRouteTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_agent_mode_sse(self):
        fake_llm = _FakeLLM(
            [
                'Thought: 需要检索\nAction: knowledge_base_search\nAction Input: {"query": "熊答是什么"}',
                "Thought: 可回答\nFinal Answer: 熊答是企业知识问答助手。",
            ]
        )
        with patch(
            "services.agent.llm_service.stream_messages", fake_llm.stream_messages
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
