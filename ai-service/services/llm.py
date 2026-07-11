"""LLM 服务 — 用 httpx 调用 OpenAI 兼容 API（火山方舟/OpenAI），支持流式生成。

无 API Key 时回退到模拟输出，保证开发阶段链路可演示。
"""

import json
from typing import AsyncGenerator

import httpx

from core.config import settings


class LlmService:
    """LLM 流式生成服务。"""

    async def stream_generate(
        self,
        question: str,
        context: str = "",
        model: str | None = None,
        history: list[dict] | None = None,
    ) -> AsyncGenerator[str, None]:
        """流式生成回答，逐 token yield。

        Args:
            question: 用户问题
            context: RAG 检索到的上下文（M1-8 无 RAG 时为空）
            model: 模型名称（可选，默认用配置）
            history: 多轮对话历史（可选，[{role, content}]，不含当前问题）
        """
        if not settings.llm_api_key:
            # 无 API Key：模拟流式输出
            demo = (
                f"我是熊答AI助手。你问的是「{question}」。\n\n"
                "当前为模拟模式（未配置 LLM API Key）。\n"
                "在 ai-service/.env 中配置 LLM_API_KEY 后即可接入真实大模型。"
            )
            for char in demo:
                yield char
            return

        # 有 API Key：调用 OpenAI 兼容 API
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

        async with httpx.AsyncClient(timeout=60.0) as client:
            async with client.stream(
                "POST",
                f"{settings.llm_base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {settings.llm_api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": model or settings.llm_model,
                    "messages": messages,
                    "stream": True,
                },
            ) as response:
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
