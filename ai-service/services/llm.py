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
        context_source: str = "kb",
    ) -> AsyncGenerator[str, None]:
        """流式生成回答，逐 token yield。

        Args:
            question: 用户问题
            context: 检索到的上下文（M1-8 无 RAG 时为空）
            model: 模型名称（可选，覆盖配置）
            history: 多轮对话历史（可选，[{role, content}]，不含当前问题）
            cfg: 运行时模型配置（由 Java 透传）；为空用 env 兜底
            client: 可选 httpx 客户端（测试注入 mock）
            no_kb_content: 检索无结果兜底标记。为 True 时在 system 指令中声明无相关内容。
            context_source: 上下文来源，"kb"（知识库）或 "web"（联网搜索，M4-3）。
        """
        cfg = cfg or ModelConfig.from_settings()
        if not cfg.has_llm():
            raise ModelConfigError("未配置 LLM API Key，请在 AI 配置页填写后重试")

        ctx_label = "知识库" if context_source == "kb" else "联网搜索"

        system_content = "你是熊答，一个企业知识问答助手。请简洁准确地回答问题。"
        if no_kb_content:
            system_content += (
                f"\n\n注意：本次{ctx_label}未找到与用户问题相关的内容。"
                "请基于你的通用知识作答，并在回答开头明确告知用户："
                f"「{ctx_label}中暂无相关内容，以下回答基于通用知识，仅供参考」。\n"
                "严禁编造或声称内容来自知识库或搜索结果。"
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
        if not base_url:
            # 提前快速失败：未配置 Base URL 时不应发起悬挂请求（避免前端一直"思考中"）
            raise ModelConfigError("未配置 LLM Base URL，请在 AI 配置页填写后重试")
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
                # connect 短超时：配置错误（地址不可达/挂起）时快速失败，避免前端长时间"思考中"
                async with httpx.AsyncClient(
                    timeout=httpx.Timeout(connect=10.0, read=120.0, write=10.0, pool=10.0)
                ) as c:
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
        """解析 SSE 流，逐 token yield 内容。

        采用 ``aiter_bytes`` 累积原始字节、按行切分后再整行 ``decode("utf-8")``，
        而非 ``aiter_lines`` 的自动解码。原因：``aiter_lines`` 在 LLM 流的分块边界
        可能把多字节中文字符截成两半，被 UTF-8 解码器替换成乱码符 U+FFFD；整行解码
        因行以 ``\\n`` 分隔、字符不会跨行截断，可彻底避免该问题。
        """
        response.raise_for_status()
        buf = b""
        async for raw in response.aiter_bytes():
            buf += raw
            while b"\n" in buf:
                line_bytes, buf = buf.split(b"\n", 1)
                try:
                    line = line_bytes.decode("utf-8").strip()
                except UnicodeDecodeError:
                    # 极端情况下字节损坏则跳过该行，避免 U+FFFD 污染
                    continue
                if not line.startswith("data: "):
                    continue
                data = line[6:]
                if data.strip() == "[DONE]":
                    return
                try:
                    chunk = json.loads(data)
                    content = chunk["choices"][0]["delta"].get("content", "")
                    if content:
                        if "\ufffd" in content:
                            logger.warning("[乱码诊断] _iter_tokens 仍出现 U+FFFD token=%r", content)
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

        同 ``_iter_tokens``，使用 ``aiter_bytes`` 累积字节整行解码，避免中文字符在流边界
        被截断成乱码符 U+FFFD。
        """
        response.raise_for_status()
        buf = b""
        done = False
        acc: dict = {}
        async for raw in response.aiter_bytes():
            buf += raw
            while b"\n" in buf:
                line_bytes, buf = buf.split(b"\n", 1)
                try:
                    line = line_bytes.decode("utf-8").strip()
                except UnicodeDecodeError:
                    continue
                if not line.startswith("data: "):
                    continue
                data = line[6:]
                if data.strip() == "[DONE]":
                    done = True
                    break
                try:
                    chunk = json.loads(data)
                    delta = chunk["choices"][0]["delta"]
                except (json.JSONDecodeError, KeyError, IndexError):
                    continue
                content = delta.get("content")
                if content:
                    if "\ufffd" in content:
                        logger.warning("[乱码诊断] _iter_tokens_with_tools 仍出现 U+FFFD token=%r", content)
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
            if done:
                break
        calls = [
            {"id": s["id"] or f"call_{i}", "name": s["name"], "arguments": s["arguments"]}
            for i, s in sorted(acc.items())
        ]
        if calls:
            yield {"type": "tool_calls", "calls": calls}


llm_service = LlmService()
