"""LLM 服务 — 用 httpx 调用 OpenAI 兼容 API（火山方舟/OpenAI），支持流式生成。

M3-3：取消静默降级（无 Key 不再回退模拟输出），未配置或调用失败时抛出可识别的
:class:`ModelConfigError`，供 Java / 前端引导用户重新配置。
"""

import json
from typing import AsyncGenerator

import httpx

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
    ) -> AsyncGenerator[str, None]:
        """流式生成回答，逐 token yield。

        Args:
            question: 用户问题
            context: RAG 检索到的上下文（M1-8 无 RAG 时为空）
            model: 模型名称（可选，覆盖配置）
            history: 多轮对话历史（可选，[{role, content}]，不含当前问题）
            cfg: 运行时模型配置（由 Java 透传）；为空用 env 兜底
            client: 可选 httpx 客户端（测试注入 mock）
        """
        cfg = cfg or ModelConfig.from_settings()
        if not cfg.has_llm():
            raise ModelConfigError("未配置 LLM API Key，请在 AI 配置页填写后重试")

        messages: list[dict] = [
            {
                "role": "system",
                "content": "你是熊答，一个企业知识问答助手。请简洁准确地回答问题。",
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

        base_url = cfg.llm_base_url or settings.llm_base_url
        model_name = model or cfg.llm_model or settings.llm_model
        try:
            if client is None:
                async with httpx.AsyncClient(timeout=60.0) as c:
                    async with c.stream(
                        "POST",
                        f"{base_url}/chat/completions",
                        headers={
                            "Authorization": f"Bearer {cfg.llm_api_key}",
                            "Content-Type": "application/json",
                        },
                        json={
                            "model": model_name,
                            "messages": messages,
                            "stream": True,
                        },
                    ) as response:
                        async for token in self._iter_tokens(response):
                            yield token
            else:
                async with client.stream(
                    "POST",
                    f"{base_url}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {cfg.llm_api_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": model_name,
                        "messages": messages,
                        "stream": True,
                    },
                ) as response:
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


llm_service = LlmService()
