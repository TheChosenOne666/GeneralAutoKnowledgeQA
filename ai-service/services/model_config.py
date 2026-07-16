"""AI 模型运行时配置 + 模型配置错误类型。

M3-3：Python 真正消费用户在界面配置的模型（由 Java 透传 ``ai_config``），取消静默降级——
API Key 缺失 / 错误、模型名错误、向量维度不匹配 均抛出 :class:`ModelConfigError`，
供 Java / 前端识别并引导用户到 /ai-config 重新配置。
"""

from dataclasses import dataclass
from typing import Optional

from core.config import settings


class ModelConfigError(Exception):
    """可识别的「模型配置错误」。

    API Key 缺失 / 错误、模型名错误、向量维度不匹配等运行时配置问题触发。
    Java 透传该错误类型给前端，引导用户到 /ai-config 重新配置。
    """


class ModelQuotaError(Exception):
    """可识别的「模型额度 / 限流错误」。

    区别于 :class:`ModelConfigError`：本类表示调用被限流（HTTP 429）、
    服务端暂时不可用（5xx 过载）或账户额度 / 余额不足，而**非**配置
    （Key / 模型名 / 维度）错误。Java 透传该错误类型给前端，提示用户
    「额度不足 / 被限流，请稍后重试或检查账户额度」，而非误导其去重配模型。
    """


# 额度 / 限流类错误的响应体关键词（大小写不敏感匹配），用于从 4xx 响应体中
# 区分「额度耗尽」与「Key / 模型名错误」两类问题。
_QUOTA_KEYWORDS = (
    "quota", "额度", "余额", "insufficient", "rate limit", "rate_limit",
    "exceeded", "frequency", "限流", "429", "too many requests", "balance",
)


def is_quota_error(text: str | None) -> bool:
    """根据响应体文本判断是否为额度 / 限流类错误（区别于配置错误）。"""
    if not text:
        return False
    low = text.lower()
    return any(k in low for k in _QUOTA_KEYWORDS)


@dataclass
class ModelConfig:
    """一次请求使用的 AI 模型配置（LLM / Embedding / Rerank）。"""

    llm_provider: Optional[str] = None
    llm_model: Optional[str] = None
    llm_api_key: Optional[str] = None
    llm_base_url: Optional[str] = None
    embedding_provider: Optional[str] = None
    embedding_model: Optional[str] = None
    embedding_api_key: Optional[str] = None
    embedding_base_url: Optional[str] = None
    embedding_dimension: Optional[int] = None
    rerank_provider: Optional[str] = None
    rerank_model: Optional[str] = None
    rerank_api_key: Optional[str] = None

    @classmethod
    def from_settings(cls) -> "ModelConfig":
        """从环境变量 / .env 配置构建。

        仅作为 standalone 调用兜底；生产环境由 Java 透传 ``ai_config``。
        """
        return cls(
            llm_provider=settings.llm_provider,
            llm_model=settings.llm_model,
            llm_api_key=settings.llm_api_key,
            llm_base_url=settings.llm_base_url,
            embedding_model=settings.embedding_model,
            embedding_api_key=settings.embedding_api_key,
            embedding_base_url=settings.embedding_base_url,
            embedding_dimension=settings.embedding_dimension,
            rerank_model=settings.rerank_model,
            rerank_api_key=settings.rerank_api_key,
        )

    @classmethod
    def from_dict(cls, d: Optional[dict]) -> Optional["ModelConfig"]:
        """从 Java 透传的 ``ai_config``（snake_case 字典）构建；为空返回 None。"""
        if not d:
            return None
        return cls(
            llm_provider=d.get("llm_provider"),
            llm_model=d.get("llm_model"),
            llm_api_key=d.get("llm_api_key"),
            llm_base_url=d.get("llm_base_url"),
            embedding_provider=d.get("embedding_provider"),
            embedding_model=d.get("embedding_model"),
            embedding_api_key=d.get("embedding_api_key"),
            embedding_base_url=d.get("embedding_base_url"),
            embedding_dimension=d.get("embedding_dimension"),
            rerank_provider=d.get("rerank_provider"),
            rerank_model=d.get("rerank_model"),
            rerank_api_key=d.get("rerank_api_key"),
        )

    def has_embedding(self) -> bool:
        """是否配置了 Embedding API Key。"""
        return bool(self.embedding_api_key)

    def has_llm(self) -> bool:
        """是否配置了 LLM API Key。"""
        return bool(self.llm_api_key)
