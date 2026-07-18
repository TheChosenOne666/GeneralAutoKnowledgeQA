"""LLM 服务单测 — 覆盖模型配置错误快速失败（M3-3 修复）。

验证：当 LLM Base URL 缺失（且 env 无兜底）时，``stream_generate`` 应
立即抛出可识别的 :class:`ModelConfigError`，而不是发起会悬挂的 httpx 请求
（避免前端长时间停在"思考中"）。

注：项目未引入 pytest-asyncio，async 协程用标准 ``asyncio.run`` 驱动。
"""
import asyncio

import pytest

from services.llm import llm_service, is_vision_model
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


# ---------- M5-9 多模态图片问答测试 ----------


def test_is_vision_model_whitelist():
    """vision 模型白名单：常见多模态模型名应命中，纯文本模型不命中。"""
    # 命中：主流多模态模型
    assert is_vision_model("gpt-4o")
    assert is_vision_model("gpt-4o-mini")
    assert is_vision_model("gpt-4-vision-preview")
    assert is_vision_model("claude-3-5-sonnet-20241022")
    assert is_vision_model("qwen-vl-max")
    assert is_vision_model("qwen2.5-vl-72b-instruct")
    assert is_vision_model("gemini-2.0-flash")
    assert is_vision_model("glm-4v")
    # 不命中：纯文本模型
    assert not is_vision_model("deepseek-chat")
    assert not is_vision_model("deepseek-v3")
    assert not is_vision_model("doubao-pro")
    assert not is_vision_model("qwen-plus")
    # 空值
    assert not is_vision_model(None)
    assert not is_vision_model("")


def test_stream_generate_with_images_rejects_non_vision_model(monkeypatch):
    """模型不支持 vision 时，stream_generate_with_images 应抛 ModelConfigError 引导用户切换。"""
    cfg = ModelConfig(
        llm_provider="deepseek",
        llm_model="deepseek-chat",  # 纯文本模型
        llm_api_key="fake-key",
        llm_base_url="https://api.deepseek.com/v1",
    )

    async def _run():
        async for _ in llm_service.stream_generate_with_images(
            question="这张图里有什么",
            image_paths=["/tmp/fake.png"],
            cfg=cfg,
        ):
            pass

    with pytest.raises(ModelConfigError) as exc_info:
        asyncio.run(_run())
    # 错误信息应引导用户切换模型
    assert "vision" in str(exc_info.value) or "多模态" in str(exc_info.value)


def test_stream_generate_with_images_falls_back_when_no_image_readable(monkeypatch, tmp_path):
    """所有图片都读取失败时，应降级为普通文本问答而非报错。"""
    cfg = ModelConfig(
        llm_provider="openai",
        llm_model="gpt-4o",  # 支持 vision
        llm_api_key="fake-key",
        llm_base_url="https://api.openai.com/v1",
    )

    # mock stream_generate，验证降级路径被调用
    calls = []

    async def _fake_stream_generate(self, question, context="", **kwargs):
        calls.append({"question": question, "context": context})
        if False:  # 让它成为 async generator
            yield ""

    monkeypatch.setattr(
        "services.llm.LlmService.stream_generate",
        lambda self, **kw: _fake_stream_generate(self, **kw),
    )

    # 用不存在的图片路径（读取失败）
    async def _run():
        async for _ in llm_service.stream_generate_with_images(
            question="问题",
            image_paths=["/nonexistent/path/image.png"],
            cfg=cfg,
        ):
            pass

    asyncio.run(_run())
    assert len(calls) == 1
    assert calls[0]["question"] == "问题"


def test_stream_generate_with_images_constructs_vision_messages(monkeypatch, tmp_path):
    """有可读图片时，应构造 OpenAI vision 格式的 messages（user.content 为 parts 数组）。"""
    cfg = ModelConfig(
        llm_provider="openai",
        llm_model="gpt-4o",
        llm_api_key="fake-key",
        llm_base_url="https://api.openai.com/v1",
    )

    # 创建一张真实的小 PNG 图片（1x1 像素）
    import base64
    png_bytes = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    )
    img_path = tmp_path / "test.png"
    img_path.write_bytes(png_bytes)

    # 拦截 _stream 拿到 messages 验证格式
    captured = {}

    async def _fake_stream(self, messages, model, cfg, client, tools=None):
        captured["messages"] = messages
        captured["model"] = model
        if False:
            yield ""

    monkeypatch.setattr("services.llm.LlmService._stream", lambda self, *a, **kw: _fake_stream(self, *a, **kw))

    async def _run():
        async for _ in llm_service.stream_generate_with_images(
            question="描述这张图",
            image_paths=[str(img_path)],
            context="附加上下文",
            cfg=cfg,
        ):
            pass

    asyncio.run(_run())

    messages = captured["messages"]
    # 第一条应是 system 消息，含附加上下文
    assert messages[0]["role"] == "system"
    assert "附加上下文" in messages[0]["content"]
    # 最后一条应是 user 消息，content 为 parts 数组
    user_msg = messages[-1]
    assert user_msg["role"] == "user"
    assert isinstance(user_msg["content"], list)
    parts = user_msg["content"]
    # 第一个 part 是 text，后续是 image_url
    assert parts[0]["type"] == "text"
    assert parts[0]["text"] == "描述这张图"
    assert any(p["type"] == "image_url" for p in parts[1:])
    # image_url 应为 data URI 形式
    img_part = next(p for p in parts[1:] if p["type"] == "image_url")
    assert img_part["image_url"]["url"].startswith("data:image/png;base64,")

