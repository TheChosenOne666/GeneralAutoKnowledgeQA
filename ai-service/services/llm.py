"""LLM 服务 — 基于 LangChain 的流式生成。

TODO: 接入 LangChain ChatOpenAI（兼容火山方舟/OpenAI API）。
"""

from typing import AsyncGenerator

from core.config import settings


class LlmService:
    """LLM 流式生成服务（骨架）。"""

    def _get_llm(self, model: str | None = None):
        """创建 LangChain LLM 实例。"""
        # TODO:
        # from langchain_openai import ChatOpenAI
        # return ChatOpenAI(
        #     model=model or settings.llm_model,
        #     api_key=settings.llm_api_key,
        #     base_url=settings.llm_base_url,
        #     streaming=True,
        # )
        raise NotImplementedError

    async def stream_generate(
        self,
        question: str,
        context: str = "",
        model: str | None = None,
    ) -> AsyncGenerator[str, None]:
        """流式生成回答，逐 token yield。

        Args:
            question: 用户问题
            context: RAG 检索到的上下文
            model: 模型名称（可选）
        """
        # TODO:
        # llm = self._get_llm(model)
        # messages = [
        #     SystemMessage(content="你是熊答，一个企业知识问答助手..."),
        #     HumanMessage(content=f"参考信息:\n{context}\n\n问题: {question}"),
        # ]
        # async for chunk in llm.astream(messages):
        #     yield chunk.content

        # 骨架阶段：模拟流式输出
        demo = "这是一条来自 AI 服务的示例回答。LangChain RAG 和 Agent 将在后续接入。"
        for char in demo:
            yield char


llm_service = LlmService()
