"""熊答 AI 服务 — 配置。"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """AI 服务配置。"""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # Application
    app_host: str = "0.0.0.0"
    app_port: int = 8001

    # LLM
    llm_provider: str = "volcengine"
    llm_model: str = "doubao-pro"
    llm_api_key: str = ""
    llm_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"

    # Embedding
    embedding_model: str = "doubao-embedding"
    embedding_api_key: str = ""
    embedding_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    embedding_dimension: int = 1536

    # Rerank
    rerank_model: str = ""
    rerank_api_key: str = ""
    rerank_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"

    # Vector DB
    vector_store_type: str = "memory"  # memory（默认，零依赖）/ milvus（需装 pymilvus + 启动服务）
    milvus_host: str = "localhost"
    milvus_port: int = 19530

    # Chunking
    chunk_size: int = 512
    chunk_overlap: int = 50

    # 检索相关性门槛：检索结果均不相关时视为「无相关文档」，不向下游推送引用来源
    # （避免用户问知识库没有的内容时，AI 仍展示不相关的错误引用来源）。
    retrieval_relevance_gate: bool = True       # 总开关
    retrieval_vector_min_relevance: float = 0.30  # 向量余弦相似度门槛（无 rerank 时使用）
    retrieval_bm25_min_relevance: float = 1.0     # BM25 分数门槛（关键词强相关时放宽向量门槛）
    retrieval_rerank_min_relevance: float = 0.10  # Rerank 相关性分数门槛（配置 rerank 时使用）

    # 检索无结果兜底策略（对齐 WeKnora）：知识库/文档为空或检索不匹配时
    #  - fixed：直接返回固定文案（不调用 LLM，省成本）
    #  - model（默认）：交给 LLM 用通用知识兜底，但须在回答中声明「知识库暂无相关内容」
    fallback_strategy: str = "model"
    fallback_response: str = "知识库暂无相关内容，如有疑问请在知识库中补充相关文档后重试。"


    # Redis（与 Java 后端共用同一实例，作为 L1 检索结果 / L2 嵌入向量缓存层）
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""
    redis_db: int = 0

    # 缓存 TTL（秒）
    retrieval_cache_ttl: int = 3600  # L1 检索结果：1h（文档变更时主动失效）
    embedding_cache_ttl: int = 86400  # L2 嵌入向量：24h（文本/模型不变则长期有效）


settings = Settings()
