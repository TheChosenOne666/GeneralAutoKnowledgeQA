"""Agent 智能增强单测 — memory 固化 / reflection 反思 / 上下文压缩（M4-C）。

桩替换 llm_service.complete（非流式）返回脚本化文本，聚焦：
- consolidate_memory：历史足够/不足/空
- reflect：can_answer=true/false / JSON 解析容错
- compress_context：超长压缩 / 不足不压缩 / 太短不值得压
"""

import asyncio
import unittest
from unittest.mock import patch

from services.agent_intelligence import (
    compress_context,
    consolidate_memory,
    estimate_chars,
    reflect,
)
from services.model_config import ModelConfig


# ── 桩 ──

async def _fake_complete_memory(messages, **kwargs):
    return "- 用户是算法工程师\n- 入职准备需了解开发环境\n- 历史对话涉及 Python 版本"


async def _fake_complete_reflect_can_answer(messages, **kwargs):
    return '{"can_answer": true, "reason": "检索结果已明确回答用户问题"}'

async def _fake_complete_reflect_cannot_answer(messages, **kwargs):
    return '{"can_answer": false, "reason": "检索结果为空，无法回答"}'

async def _fake_complete_reflect_malformed(messages, **kwargs):
    return '我认为可以回答 {"can_answer": true, "reason": "结果充分"}'

async def _fake_complete_reflect_bad_json(messages, **kwargs):
    return "这不是 JSON"

async def _fake_complete_compress(messages, **kwargs):
    return "历史对话摘要：算法工程师入职需配置 Python 环境与 IDE。"


# ── Memory 固化 ──

class MemoryConsolidationTest(unittest.TestCase):
    def test_sufficient_history_returns_memory_block(self):
        history = [
            {"role": "user", "content": "hello"},
            {"role": "assistant", "content": "hi"},
            {"role": "user", "content": "算法入职需要准备什么"},
            {"role": "assistant", "content": "需要安装 Python"},
            {"role": "user", "content": "Python 版本有要求吗"},
            {"role": "assistant", "content": "推荐 3.10+"},
        ]
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_memory,
        ):
            result = asyncio.run(
                consolidate_memory(history, "IDE 用什么", ModelConfig())
            )
        self.assertIn("算法工程师", result)
        self.assertIn("Python", result)

    def test_insufficient_history_returns_empty(self):
        history = [
            {"role": "user", "content": "hello"},
            {"role": "assistant", "content": "hi"},
        ]
        result = asyncio.run(
            consolidate_memory(history, "你好", ModelConfig())
        )
        self.assertEqual(result, "")

    def test_empty_history_returns_empty(self):
        result = asyncio.run(consolidate_memory([], "hello", ModelConfig()))
        self.assertEqual(result, "")

    def test_none_history_returns_empty(self):
        result = asyncio.run(consolidate_memory(None, "hello", ModelConfig()))
        self.assertEqual(result, "")

    def test_llm_error_returns_empty(self):
        async def _fail(messages, **kwargs):
            raise RuntimeError("LLM down")

        history = [
            {"role": "user", "content": "a"}, {"role": "assistant", "content": "b"},
            {"role": "user", "content": "c"}, {"role": "assistant", "content": "d"},
        ]
        with patch(
            "services.agent_intelligence.llm_service.complete", _fail
        ):
            result = asyncio.run(
                consolidate_memory(history, "x", ModelConfig())
            )
        self.assertEqual(result, "")


# ── Reflection 反思 ──

class ReflectionTest(unittest.TestCase):
    def test_can_answer_true(self):
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_reflect_can_answer,
        ):
            result = asyncio.run(
                reflect("算法入职准备什么", "找到了环境配置文档", 0, ModelConfig())
            )
        self.assertTrue(result["can_answer"])

    def test_can_answer_false(self):
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_reflect_cannot_answer,
        ):
            result = asyncio.run(
                reflect(
                    "算法入职准备什么",
                    "未找到相关内容",
                    0,
                    ModelConfig(),
                )
            )
        self.assertFalse(result["can_answer"])

    def test_malformed_json_with_extra_text(self):
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_reflect_malformed,
        ):
            result = asyncio.run(
                reflect("x", "y", 0, ModelConfig())
            )
        self.assertTrue(result["can_answer"])
        self.assertIn("结果充分", result["reason"])

    def test_bad_json_defaults_to_continue(self):
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_reflect_bad_json,
        ):
            result = asyncio.run(
                reflect("x", "y", 0, ModelConfig())
            )
        # 解析失败 → 默认 can_answer=False，继续检索
        self.assertFalse(result["can_answer"])


# ── 上下文压缩 ──

class ContextCompressionTest(unittest.TestCase):
    def test_estimate_chars(self):
        msgs = [
            {"role": "system", "content": "你是助手"},
            {"role": "user", "content": "问题"},
            {"role": "assistant", "content": "回答" * 100},
        ]
        self.assertGreater(estimate_chars(msgs), 200)

    def test_compress_reduces_message_count(self):
        messages = [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q1"},
            {"role": "assistant", "content": "a1" * 50},
            {"role": "tool", "content": "t1" * 50},
            {"role": "user", "content": "q2"},
            {"role": "assistant", "content": "a2" * 50},
            {"role": "tool", "content": "t2" * 50},
            {"role": "user", "content": "q3"},
            {"role": "assistant", "content": "a3"},
            {"role": "tool", "content": "t3"},
            {"role": "user", "content": "q4"},
            {"role": "assistant", "content": "a4"},
        ]
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_compress,
        ):
            result = asyncio.run(
                compress_context(messages, ModelConfig())
            )
        self.assertLess(len(result), len(messages))
        self.assertIn("历史摘要", "".join(
            m.get("content", "") for m in result if m["role"] == "system"
        ))

    def test_no_compress_when_messages_too_short(self):
        messages = [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q1"},
            {"role": "assistant", "content": "a1"},
        ]
        original = list(messages)
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_compress,
        ):
            result = asyncio.run(
                compress_context(messages, ModelConfig())
            )
        self.assertEqual(len(result), len(original))

    def test_compressible_content_too_small_skips(self):
        # 旧消息内容太少不值得压缩
        messages = [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q1"},
            {"role": "assistant", "content": "ok"},
            {"role": "tool", "content": "..."},
            {"role": "user", "content": "q2"},
            {"role": "assistant", "content": "ok"},
            {"role": "tool", "content": "..."},
            {"role": "user", "content": "q3"},
            {"role": "assistant", "content": "a3"},
            {"role": "tool", "content": "t3"},
            {"role": "user", "content": "q4"},
            {"role": "assistant", "content": "a4"},
        ]
        with patch(
            "services.agent_intelligence.llm_service.complete",
            _fake_complete_compress,
        ):
            result = asyncio.run(
                compress_context(messages, ModelConfig())
            )
        self.assertEqual(len(result), len(messages))

    def test_compress_error_falls_back(self):
        async def _fail(messages, **kwargs):
            raise RuntimeError("compress failed")

        messages = [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q1"},
            {"role": "assistant", "content": "a" * 100},
            {"role": "tool", "content": "t" * 100},
            {"role": "user", "content": "q2"},
            {"role": "assistant", "content": "a" * 100},
            {"role": "tool", "content": "t" * 100},
            {"role": "user", "content": "q3"},
            {"role": "assistant", "content": "a3"},
            {"role": "tool", "content": "t3"},
            {"role": "user", "content": "q4"},
            {"role": "assistant", "content": "a4"},
        ]
        with patch(
            "services.agent_intelligence.llm_service.complete", _fail
        ):
            result = asyncio.run(
                compress_context(messages, ModelConfig())
            )
        # 失败时返回原列表
        self.assertEqual(len(result), len(messages))


if __name__ == "__main__":
    unittest.main()
