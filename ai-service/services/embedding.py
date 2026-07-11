"""Embedding 服务 — 文本向量化。

真实路径用 httpx 调用 OpenAI 兼容 Embedding 接口（兼容火山方舟）；
未配置 API Key 时降级为确定性伪向量（hashing trick），保证开发/单测/演示链路可跑通。

不引入 langchain-openai 重依赖，直接调用 /embeddings 接口，与 llm.py 的 httpx 风格一致。
"""

import hashlib
import json
import math
import re

import httpx
from loguru import logger

from core.config import settings
from core.redis_client import get_redis


class EmbeddingService:
    """文本向量化服务。"""

    def _cache_key(self, text: str) -> str:
        """L2 缓存 key：embedding:{text_hash}:{model}。"""
        text_hash = hashlib.md5(text.encode("utf-8")).hexdigest()
        return f"embedding:{text_hash}:{settings.embedding_model}"

    async def embed_text(self, text: str) -> list[float]:
        """将单段文本向量化（带 L2 Redis 缓存）。

        有 embedding_api_key 时调用远程 OpenAI 兼容接口；否则降级为确定性伪向量。
        Redis 不可用时降级为直接计算（不缓存），不阻塞主流程。
        """
        cache_key = self._cache_key(text)
        try:
            r = await get_redis()
            cached = await r.get(cache_key)
            if cached is not None:
                return json.loads(cached)
        except Exception as e:
            logger.warning(f"读取嵌入缓存失败，降级直接计算: {e}")

        vec = (
            (await self._embed_remote([text]))[0]
            if settings.embedding_api_key
            else self._embed_fallback(text)
        )

        try:
            r = await get_redis()
            await r.set(cache_key, json.dumps(vec), ex=settings.embedding_cache_ttl)
        except Exception as e:
            logger.warning(f"写入嵌入缓存失败，忽略: {e}")
        return vec

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """批量向量化（逐条命中 L2 缓存，未命中的批量计算后回写）。"""
        if not texts:
            return []
        results: list[list[float] | None] = [None] * len(texts)
        miss_indices: list[int] = []
        try:
            r = await get_redis()
            for i, t in enumerate(texts):
                cached = await r.get(self._cache_key(t))
                if cached is not None:
                    results[i] = json.loads(cached)
                else:
                    miss_indices.append(i)
        except Exception as e:
            logger.warning(f"批量读嵌入缓存失败，降级全部重算: {e}")
            miss_indices = list(range(len(texts)))

        if miss_indices:
            if settings.embedding_api_key:
                miss_vecs = await self._embed_remote([texts[i] for i in miss_indices])
            else:
                miss_vecs = [self._embed_fallback(texts[i]) for i in miss_indices]
            for pos, idx in enumerate(miss_indices):
                results[idx] = miss_vecs[pos]
            try:
                r = await get_redis()
                for idx in miss_indices:
                    await r.set(
                        self._cache_key(texts[idx]),
                        json.dumps(results[idx]),
                        ex=settings.embedding_cache_ttl,
                    )
            except Exception as e:
                logger.warning(f"批量写嵌入缓存失败，忽略: {e}")
        return [v for v in results if v is not None]

    async def _embed_remote(self, texts: list[str]) -> list[list[float]]:
        """调用 OpenAI 兼容 Embedding 接口（批量）。"""
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(
                f"{settings.embedding_base_url}/embeddings",
                headers={
                    "Authorization": f"Bearer {settings.embedding_api_key}",
                    "Content-Type": "application/json",
                },
                json={"model": settings.embedding_model, "input": texts},
            )
            resp.raise_for_status()
            data = resp.json()["data"]
            # 按 index 排序，确保与输入顺序一致
            data.sort(key=lambda d: d.get("index", 0))
            return [d["embedding"] for d in data]

    def _embed_fallback(self, text: str) -> list[float]:
        """确定性伪向量（hashing trick + L2 归一化）。

        对文本分词后哈希映射到维度空间累加词频，再归一化。
        词汇重叠度高的文本余弦相似度更高，便于开发/演示检索。
        """
        dim = settings.embedding_dimension
        vec = [0.0] * dim
        # 字符级 bigram 分词：对中文/英文都有效，避免 \w+ 把整句当一个 token
        norm_text = text.lower().replace(" ", "")
        tokens = (
            [norm_text[i : i + 2] for i in range(len(norm_text) - 1)]
            if len(norm_text) > 1
            else [norm_text]
        )
        for tok in tokens:
            h = int(hashlib.md5(tok.encode("utf-8")).hexdigest(), 16)
            vec[h % dim] += 1.0
        norm = math.sqrt(sum(v * v for v in vec))
        if norm > 0:
            vec = [v / norm for v in vec]
        return vec


embedding_service = EmbeddingService()
