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

    # Vector DB —— 默认 pgvector（Postgres 持久化，对齐 WeKnora，重启不丢知识）
    # 可选：memory（零依赖演示）/ milvus（需装 pymilvus + 启动服务）
    vector_store_type: str = "pgvector"
    milvus_host: str = "localhost"
    milvus_port: int = 19530

    # Postgres（向量持久化存储，默认与 Java 后端同源；可用 .env 覆盖）
    pg_host: str = "localhost"
    pg_port: int = 5432
    pg_user: str = "postgres"
    pg_password: str = "postgres123!@#"
    pg_db: str = "xiongda"

    # Chunking
    chunk_size: int = 512
    chunk_overlap: int = 50

    # 检索相关性门槛：检索结果均不相关时视为「无相关文档」，不向下游推送引用来源
    # （避免用户问知识库没有的内容时，AI 仍展示不相关的错误引用来源）。
    retrieval_relevance_gate: bool = True       # 总开关
    retrieval_vector_min_relevance: float = 0.30  # 向量余弦相似度门槛（无 rerank 时使用）
    retrieval_bm25_min_relevance: float = 1.0     # BM25 分数门槛（关键词强相关时放宽向量门槛）
    retrieval_rerank_min_relevance: float = 0.10  # Rerank 相关性分数门槛（配置 rerank 时使用）

    # 普通问答增强（对齐 WeKnora KnowledgeQA 的 query rewrite + query expansion）：
    # 仅作用于 rag 模式（retrieve(enhance=True) 时触发），Agent 模式不开启（Agent 内部
    # 由 LLM 自生成子查询，不做二次改写，避免画蛇添足）。
    enable_query_rewrite: bool = True    # 检索前用 LLM 把口语化问题改写成检索友好 query
    enable_query_expansion: bool = True  # 主检索召回不足时用 LLM 生成扩展查询再检索并 RRF 合并
    retrieval_expansion_min: int = 3     # 主检索结果数 < 此值才触发 expansion（避免无谓额外调用）

    # 检索无结果兜底策略（对齐 WeKnora）：知识库/文档为空或检索不匹配时
    #  - fixed：直接返回固定文案（不调用 LLM，省成本）
    #  - model（默认）：交给 LLM 用通用知识兜底，但须在回答中声明「知识库暂无相关内容」
    fallback_strategy: str = "model"
    fallback_response: str = "知识库暂无相关内容，如有疑问请在知识库中补充相关文档后重试。"

    # Agent 智能增强（M4-C 轻量增强）：
    # - memory 固化：长对话时从历史提取关键事实为记忆块注入 system prompt
    # - reflection 反思：每轮工具调用后 LLM 自评信息是否足够回答
    # - 上下文压缩：messages 超长时压缩旧观察，防超出 LLM 上下文窗口
    enable_agent_memory: bool = True
    enable_agent_reflection: bool = True
    enable_agent_compression: bool = True
    agent_memory_min_messages: int = 4  # history 至少 N 条消息才触发 memory 固化
    agent_context_max_chars: int = 12000  # messages 字符数超此阈值触发压缩（≈6k tokens）

    # M4-3 联网搜索配置
    enable_web_search: bool = True
    web_search_max_results: int = 5  # 每次联网搜索返回条数
    web_search_timeout: float = 15.0  # 联网搜索超时秒数


    # Redis（与 Java 后端共用同一实例，作为 L1 检索结果 / L2 嵌入向量缓存层）
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""
    redis_db: int = 0

    # 缓存 TTL（秒）
    retrieval_cache_ttl: int = 3600  # L1 检索结果：1h（文档变更时主动失效）
    embedding_cache_ttl: int = 86400  # L2 嵌入向量：24h（文本/模型不变则长期有效）


settings = Settings()
