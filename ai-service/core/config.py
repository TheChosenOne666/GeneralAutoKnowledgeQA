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

    # Redis（与 Java 后端共用同一实例，作为 L1 检索结果 / L2 嵌入向量缓存层）
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""
    redis_db: int = 0

    # 缓存 TTL（秒）
    retrieval_cache_ttl: int = 3600  # L1 检索结果：1h（文档变更时主动失效）
    embedding_cache_ttl: int = 86400  # L2 嵌入向量：24h（文本/模型不变则长期有效）


settings = Settings()
