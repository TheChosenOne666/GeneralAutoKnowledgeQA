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

    # Vector DB
    milvus_host: str = "localhost"
    milvus_port: int = 19530

    # Chunking
    chunk_size: int = 512
    chunk_overlap: int = 50


settings = Settings()
