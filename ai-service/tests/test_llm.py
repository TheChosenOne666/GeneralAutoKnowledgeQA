"""LLM 服务单测 — 覆盖模型配置错误快速失败（M3-3 修复）。

验证：当 LLM Base URL 缺失（且 env 无兜底）时，``stream_generate`` 应
立即抛出可识别的 :class:`ModelConfigError`，而不是发起会悬挂的 httpx 请求
（避免前端长时间停在"思考中"）。

注：项目未引入 pytest-asyncio，async 协程用标准 ``asyncio.run`` 驱动。
"""
import asyncio

import pytest

from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError


def test_stream_generate_raises_when_base_url_missing(monkeypatch):
    """base_url 为空且无 env 兜底时，立即抛 ModelConfigError（不发起悬挂请求）。"""
    monkeypatch.setattr("services.llm.settings.llm_base_url", None)
    cfg = ModelConfig(
        llm_provider="openai",
        llm_model="gpt-4",
        llm_api_key="fake-key",
        llm_base_url=None,
    )

    async def _run():
        async for _ in llm_service.stream_generate(question="hi", cfg=cfg):
            pass

    with pytest.raises(ModelConfigError):
        asyncio.run(_run())


def test_iter_tokens_decodes_utf8_chinese():
    """LLM SSE 响应必须按 UTF-8 解码，避免多字节中文被替换成乱码符 U+FFFD。

    构造无 charset 的 SSE 字节流（含中文），验证 ``_iter_tokens`` 正确还原中文，
    且显式将 ``response.encoding`` 置为 UTF-8（不依赖 httpx 对编码的猜测）。
    """
    import httpx

    raw = (
        'data: {"choices":[{"delta":{"content":"抓包工具"}}]}\n\n'
        'data: [DONE]\n\n'
    ).encode("utf-8")
    req = httpx.Request("POST", "http://test")
    resp = httpx.Response(200, request=req, content=raw, headers={"Content-Type": "text/event-stream"})

    async def _run():
        gen = llm_service._iter_tokens(resp)
        token = await gen.__anext__()
        return token, resp.encoding

    token, enc = asyncio.run(_run())
    assert token == "抓包工具"
    assert enc == "utf-8"


def test_iter_tokens_with_tools_decodes_utf8_chinese():
    """function-calling 流式解析同样必须按 UTF-8 解码（M4-B 智能体模式）。"""
    import httpx

    raw = (
        'data: {"choices":[{"delta":{"content":"接口测试工具"}}]}\n\n'
        'data: [DONE]\n\n'
    ).encode("utf-8")
    req = httpx.Request("POST", "http://test")
    resp = httpx.Response(200, request=req, content=raw, headers={"Content-Type": "text/event-stream"})

    async def _run():
        gen = llm_service._iter_tokens_with_tools(resp)
        ev = await gen.__anext__()
        return ev, resp.encoding

    ev, enc = asyncio.run(_run())
    assert ev == {"type": "token", "content": "接口测试工具"}
    assert enc == "utf-8"

