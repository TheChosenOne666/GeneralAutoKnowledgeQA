"""LLM 服务 — 用 httpx 调用 OpenAI 兼容 API（火山方舟/OpenAI），支持流式生成。

M3-3：取消静默降级（无 Key 不再回退模拟输出），未配置或调用失败时抛出可识别的
:class:`ModelConfigError`，供 Java / 前端引导用户重新配置。
"""

import json
from typing import AsyncGenerator

import httpx
from loguru import logger

from core.config import settings
from services.model_config import ModelConfig, ModelConfigError


class LlmService:
    """LLM 流式生成服务。"""

    async def stream_generate(
        self,
        question: str,
        context: str = "",
        model: str | None = None,
        history: list[dict] | None = None,
        cfg: ModelConfig | None = None,
        client: httpx.AsyncClient | None = None,
        no_kb_content: bool = False,
    ) -> AsyncGenerator[str, None]:
        """流式生成回答，逐 token yield。

        Args:
            question: 用户问题
            context: RAG 检索到的上下文（M1-8 无 RAG 时为空）
            model: 模型名称（可选，覆盖配置）
            history: 多轮对话历史（可选，[{role, content}]，不含当前问题）
            cfg: 运行时模型配置（由 Java 透传）；为空用 env 兜底
            client: 可选 httpx 客户端（测试注入 mock）
            no_kb_content: 检索无结果兜底标记。为 True 时知识库未检索到相关内容，
                在 system 指令中要求 LLM 用通用知识兜底并声明「知识库暂无相关内容」，严禁编造。
        """
        cfg = cfg or ModelConfig.from_settings()
        if not cfg.has_llm():
            raise ModelConfigError("未配置 LLM API Key，请在 AI 配置页填写后重试")

        system_content = "你是熊答，一个企业知识问答助手。请简洁准确地回答问题。"
        if no_kb_content:
            system_content += (
                "\n\n注意：本次检索中知识库未找到与用户问题相关的内容。"
                "请基于你的通用知识作答，并在回答开头明确告知用户："
                "「知识库中暂无相关内容，以下回答基于通用知识，仅供参考」。\n"
                "严禁编造或声称内容来自知识库。"
            )

        messages: list[dict] = [
            {
                "role": "system",
                "content": system_content,
            },
        ]
        for h in history or []:
            role = h.get("role")
            if role in ("user", "assistant"):
                messages.append({"role": role, "content": h.get("content", "")})
        user_content = (
            f"参考信息:\n{context}\n\n问题: {question}" if context else question
        )
        messages.append({"role": "user", "content": user_content})

        async for token in self._stream(messages, model, cfg, client):
            yield token

    async def stream_messages(
        self,
        messages: list[dict],
        model: str | None = None,
        cfg: ModelConfig | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> AsyncGenerator[str, None]:
        """给定完整 messages 列表流式生成（逐 token yield）。

        M4-1 Agent ReAct 循环使用：每轮把累积的推理转录作为 messages 传入，
        由调用方控制 system / history / 工具观察的完整上下文。
        """
        cfg = cfg or ModelConfig.from_settings()
        if not cfg.has_llm():
            raise ModelConfigError("未配置 LLM API Key，请在 AI 配置页填写后重试")
        async for token in self._stream(messages, model, cfg, client):
            yield token

    async def complete(
        self,
        messages: list[dict],
        model: str | None = None,
        cfg: ModelConfig | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> str:
        """非流式生成：累积 token 返回完整文本（供 query rewrite / expansion 等内部调用）。

        复用 :meth:`_stream` 的底层调用与鉴权逻辑；模型配置错误同样抛出
        :class:`ModelConfigError` 供上层透传。
        """
        cfg = cfg or ModelConfig.from_settings()
        if not cfg.has_llm():
            raise ModelConfigError("未配置 LLM API Key，请在 AI 配置页填写后重试")
        parts: list[str] = []
        async for tok in self._stream(messages, model, cfg, client):
            parts.append(tok)
        return "".join(parts)

    async def _stream(
        self,
        messages: list[dict],
        model: str | None,
        cfg: ModelConfig,
        client: httpx.AsyncClient | None,
        tools: list[dict] | None = None,
    ):
        """流式调用 OpenAI 兼容 chat/completions 的核心实现（共享于上游方法）。

        ``tools`` 为空时逐 token yield 文本（供普通问答 / ReAct 降级）；
        ``tools`` 给定时（M4-B function calling）yield 字典事件：
        ``{"type": "token", "content": ...}`` 与流末 ``{"type": "tool_calls", "calls": [...]}``。
        """
        base_url = cfg.llm_base_url or settings.llm_base_url
        model_name = model or cfg.llm_model or settings.llm_model
        _llm_key = cfg.llm_api_key or ""
        _llm_tail = "***" + _llm_key[-4:] if _llm_key else "null"
        logger.info(f"[M3-3诊断] LLM请求 base_url={base_url} model={model_name} key尾4={_llm_tail} tools={'on' if tools else 'off'}")
        json_body = {"model": model_name, "messages": messages, "stream": True}
        if tools:
            json_body["tools"] = tools
            json_body["tool_choice"] = "auto"
        headers = {
            "Authorization": f"Bearer {cfg.llm_api_key}",
            "Content-Type": "application/json",
        }
        try:
            if client is None:
                async with httpx.AsyncClient(timeout=60.0) as c:
                    async with c.stream(
                        "POST", f"{base_url}/chat/completions", headers=headers, json=json_body
                    ) as response:
                        if tools:
                            async for ev in self._iter_tokens_with_tools(response):
                                yield ev
                        else:
                            async for token in self._iter_tokens(response):
                                yield token
            else:
                async with client.stream(
                    "POST", f"{base_url}/chat/completions", headers=headers, json=json_body
                ) as response:
                    if tools:
                        async for ev in self._iter_tokens_with_tools(response):
                            yield ev
                    else:
                        async for token in self._iter_tokens(response):
                            yield token
        except httpx.HTTPError as e:
            raise ModelConfigError(f"LLM 调用失败（模型名或 API Key 可能错误）：{e}") from e

    @staticmethod
    async def _iter_tokens(response: httpx.Response) -> AsyncGenerator[str, None]:
        """解析 SSE 流，逐 token yield 内容。"""
        response.raise_for_status()
        async for line in response.aiter_lines():
            if not line.startswith("data: "):
                continue
            data = line[6:]
            if data.strip() == "[DONE]":
                break
            try:
                chunk = json.loads(data)
                content = chunk["choices"][0]["delta"].get("content", "")
                if content:
                    yield content
            except (json.JSONDecodeError, KeyError, IndexError):
                continue

    async def stream_agent_turn(
        self,
        messages: list[dict],
        tools: list[dict],
        model: str | None = None,
        cfg: ModelConfig | None = None,
        client: httpx.AsyncClient | None = None,
    ):
        """Agent 单轮生成：流式 yield 文本 token 与工具调用事件（M4-B function calling）。

        Yields:
            - ``{"type": "token", "content": str}``：每片文本（思考 / 最终答案）
            - ``{"type": "tool_calls", "calls": [{"id", "name", "arguments"}]}``：流末一次，
              含本轮所有工具调用；``arguments`` 为累积的完整 JSON 字符串，由调用方解析。
        """
        cfg = cfg or ModelConfig.from_settings()
        if not cfg.has_llm():
            raise ModelConfigError("未配置 LLM API Key，请在 AI 配置页填写后重试")
        async for ev in self._stream(messages, model, cfg, client, tools=tools):
            yield ev

    @staticmethod
    async def _iter_tokens_with_tools(response: httpx.Response):
        """解析 function-calling 流式响应：累积 tool_calls 分片同时透传 content token。

        OpenAI 兼容 API 的 ``delta.tool_calls`` 采用分片增量（``index`` 标识同一调用，
        ``function.arguments`` 逐片拼接），流末一次性产出完整工具调用列表。
        """
        response.raise_for_status()
        acc: dict = {}
        async for line in response.aiter_lines():
            if not line.startswith("data: "):
                continue
            data = line[6:]
            if data.strip() == "[DONE]":
                break
            try:
                chunk = json.loads(data)
                delta = chunk["choices"][0]["delta"]
            except (json.JSONDecodeError, KeyError, IndexError):
                continue
            content = delta.get("content")
            if content:
                yield {"type": "token", "content": content}
            for tc in delta.get("tool_calls") or []:
                idx = tc.get("index", 0)
                slot = acc.setdefault(idx, {"id": "", "name": "", "arguments": ""})
                if tc.get("id"):
                    slot["id"] = tc["id"]
                fn = tc.get("function") or {}
                if fn.get("name"):
                    slot["name"] += fn["name"]
                if fn.get("arguments"):
                    slot["arguments"] += fn["arguments"]
        calls = [
            {"id": s["id"] or f"call_{i}", "name": s["name"], "arguments": s["arguments"]}
            for i, s in sorted(acc.items())
        ]
        if calls:
            yield {"type": "tool_calls", "calls": calls}


llm_service = LlmService()
