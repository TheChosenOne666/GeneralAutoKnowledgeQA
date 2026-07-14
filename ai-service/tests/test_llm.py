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
