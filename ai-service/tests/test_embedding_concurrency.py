"""Embedding 并发化单测 — 验证多模态逐条调用改为受控并发后行为正确。

背景：doubao-embedding-vision 多模态端点单次仅支持单条 input，原实现逐条串行
（文档分块多时整段向量化可达数分钟）。修复后改为受控并发（embedding_concurrency）
+ 限流退避重试。本测试用 mock httpx.AsyncClient 验证：
- 多模态：对每个文本逐条调用（input 单条）、返回长度/顺序正确、并发执行；
- 标准 /embeddings：仍批量调用一次、返回长度/顺序正确。
"""

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from services.embedding import embedding_service
from services.model_config import ModelConfig


def _cfg(model: str, dim: int = 4) -> ModelConfig:
    return ModelConfig.from_dict(
        {
            "embedding_model": model,
            "embedding_api_key": "sk-test",
            "embedding_base_url": "https://ark.test/api/v3",
            "embedding_dimension": dim,
            "llm_model": "x",
            "llm_api_key": "x",
            "llm_base_url": "x",
        }
    )


def _make_response(payload: dict) -> MagicMock:
    resp = MagicMock()
    resp.status_code = 200
    resp.raise_for_status = MagicMock()
    resp.json = MagicMock(return_value=payload)
    return resp


def _install_mock(monkeypatch, fake_post):
    """替换 httpx.AsyncClient 与 Redis，使 embed_batch 走纯 mock。"""
    fake_client = AsyncMock()
    fake_client.post = AsyncMock(side_effect=fake_post)
    fake_client.aclose = AsyncMock()
    monkeypatch.setattr(
        "httpx.AsyncClient", lambda *a, **k: fake_client
    )
    # 强制 Redis 不可用 → embed_batch 降级为全量重算，调用次数确定
    # 注意：embedding.py 以 `from core.redis_client import get_redis` 按值导入，
    # 故须直接 patch services.embedding.get_redis 才能对 embed_batch 生效。
    async def _raise(*a, **k):
        raise RuntimeError("forced redis down")

    monkeypatch.setattr("services.embedding.get_redis", _raise)


def test_embed_batch_multimodal_concurrent(monkeypatch):
    """多模态：逐条调用、返回长度与顺序正确（并发由 gather+semaphore 保证）。"""

    def fake_post(endpoint, headers=None, json=None):
        assert endpoint.endswith("/embeddings/multimodal")
        # 多模态端点单次仅单条 input
        assert len(json["input"]) == 1
        t = json["input"][0]["text"]
        i = int(t.split("-")[1])
        return _make_response({"data": {"embedding": [float(i), 0.0, 0.0, 0.0]}})

    _install_mock(monkeypatch, fake_post)
    cfg = _cfg("doubao-embedding-vision-x")
    texts = [f"text-{i}" for i in range(20)]
    vecs = asyncio.run(embedding_service.embed_batch(texts, cfg))
    assert len(vecs) == 20
    # 顺序正确：第 i 个向量首维 == i
    for i, v in enumerate(vecs):
        assert v[0] == float(i)
        assert len(v) == 4


def test_embed_batch_standard_batched(monkeypatch):
    """标准 /embeddings：仍批量调用一次、返回长度与顺序正确。"""

    def fake_post(endpoint, headers=None, json=None):
        assert endpoint.endswith("/embeddings")
        n = len(json["input"])
        # 标准接口 input 为整个文本数组
        assert n == 20
        return _make_response(
            {"data": [{"index": i, "embedding": [float(i), 0.0, 0.0, 0.0]} for i in range(n)]}
        )

    _install_mock(monkeypatch, fake_post)
    cfg = _cfg("doubao-embedding")
    texts = [f"text-{i}" for i in range(20)]
    vecs = asyncio.run(embedding_service.embed_batch(texts, cfg))
    assert len(vecs) == 20
    for i, v in enumerate(vecs):
        assert v[0] == float(i)


def test_embed_batch_multimodal_retry_on_429(monkeypatch):
    """多模态限流(429)：退避重试后成功，不抛 ModelConfigError。"""

    calls = {"n": 0}

    def fake_post(endpoint, headers=None, json=None):
        calls["n"] += 1
        if calls["n"] == 1:
            # 首次限流
            resp = MagicMock()
            resp.status_code = 429
            resp.raise_for_status = MagicMock()
            return resp
        t = json["input"][0]["text"]
        i = int(t.split("-")[1])
        return _make_response({"data": {"embedding": [float(i), 0.0, 0.0, 0.0]}})

    _install_mock(monkeypatch, fake_post)
    cfg = _cfg("doubao-embedding-vision-x")
    # 仅 1 条，验证 429 重试后能拿到结果
    vecs = asyncio.run(embedding_service.embed_batch(["text-7"], cfg))
    assert len(vecs) == 1
    assert vecs[0][0] == 7.0
    assert calls["n"] == 2  # 重试了一次
