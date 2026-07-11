"""Embedding 服务 — 文本向量化。

TODO: 接入 LangChain Embedding + 火山方舟 API。
"""

from core.config import settings


class EmbeddingService:
    """文本向量化服务（骨架）。"""

    def _get_embedding(self):
        """创建 LangChain Embedding 实例。"""
        # TODO:
        # from langchain_openai import OpenAIEmbeddings
        # return OpenAIEmbeddings(
        #     model=settings.embedding_model,
        #     api_key=settings.embedding_api_key,
        #     base_url=settings.embedding_base_url,
        # )
        raise NotImplementedError

    async def embed_text(self, text: str) -> list[float]:
        """将单段文本向量化。"""
        # embedding = self._get_embedding()
        # return await embedding.aembed_query(text)
        raise NotImplementedError

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """批量向量化。"""
        # embedding = self._get_embedding()
        # return await embedding.aembed_documents(texts)
        raise NotImplementedError


embedding_service = EmbeddingService()
