"""熊答 AI 服务 — 配置。"""

from pydantic import AliasChoices, Field

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """AI 服务配置。"""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # Application
    app_host: str = "0.0.0.0"
    app_port: int = 8001

    # 后端 Java 服务地址（Python 在处理阶段回调更新文档状态用）
    backend_base_url: str = "http://localhost:8080"

    # 检索优化（retrieval augmentation）：基于分块生成问答对并入库增强检索
    enable_qa_augment: bool = True
    # 单文档最多生成的问答对数量（限制 LLM 调用成本）
    qa_max_pairs: int = 20
    # 问答增强并发度（对标业界成熟方案：分批并发生成而非单线程串行，降低「优化中」耗时）
    qa_concurrency: int = 5
    # 单个问答对的 LLM 生成超时（秒），超时则该块跳过，不阻塞整篇增强
    qa_per_qa_timeout: float = 120.0
    # 问答增强后台任务总超时（秒）：超时放弃剩余问答对，文档仍维持可检索并推进 ready
    qa_background_timeout: float = 600.0

    # M5-7 增强内容丰富度扩展：在问答对(qa)增强基础上扩展 summary/question/wiki/entity
    # 四类增强块，提升检索召回与知识覆盖度（对标业界 postprocess: 摘要+问题+实体关系+Auto-Wiki）。
    enable_augment_extensions: bool = True   # 总开关：是否生成扩展增强块
    augment_ext_summary: bool = True         # 文档级摘要块（chunk_type=summary）
    augment_ext_question: bool = True        # 推测用户问题块（chunk_type=question）
    augment_ext_wiki: bool = True            # Auto-Wiki 条目块（chunk_type=wiki）
    augment_ext_entity: bool = True          # 实体关系(GraphRAG)块（chunk_type=entity，三元组存 metadata）
    augment_ext_summary_max_chars: int = 6000   # 喂 LLM 做摘要/实体/wiki 的文档拼接字符上限
    augment_ext_question_max: int = 12       # 推测问题块数量上限
    augment_ext_wiki_max: int = 5            # Auto-Wiki 条目上限
    augment_ext_entity_max: int = 20         # 实体关系三元组上限
    augment_ext_per_call_timeout: float = 120.0  # 单类扩展增强 LLM 调用超时（秒）

    # M5-6 多模态增强（图片 OCR + VLM caption）：解析阶段抽取文档图片，对每张图用 VLM
    # （复用 M3-3 配置的 LLM，需支持多模态/图片输入）做一次调用，同时产出 OCR 文字块
    # （chunk_type=ocr）与图像描述块（chunk_type=image_caption），均入向量库参与检索
    # （默认排除为引用来源，并入 AUGMENT_CHUNK_TYPES，与 qa 增强块同语义）。
    enable_multimodal: bool = True                 # 总开关：是否抽取图片做 OCR/caption 增强
    multimodal_max_images: int = 20               # 每文档最多抽取图片数（避免大 PDF 图片爆炸）
    multimodal_min_image_bytes: int = 1024        # 跳过过小的图片（图标/字形/分隔线等噪声）
    multimodal_ocr_caption_concurrency: int = 4   # 图片级 OCR+caption 并发度
    multimodal_per_image_timeout: float = 120.0   # 单张图片 VLM 调用超时（秒）

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

    # 多模态 Embedding（doubao-embedding-vision 系列）的 /embeddings/multimodal 端点
    # 单次仅支持单条 input，无法批量；文档分块数多时若逐条串行调用会极慢
    #（3000+ 块可达数分钟，且期间取消无法中断）。用受控并发替代串行（同时作用于
    # 限流退避重试），显著压缩整段向量化耗时。标准 /embeddings 批量接口不受影响。
    embedding_concurrency: int = 16

    # Rerank
    rerank_model: str = ""
    rerank_api_key: str = ""
    rerank_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"

    # Vector DB —— 默认 pgvector（Postgres 持久化，对标业界成熟方案，重启不丢知识）
    # 可选：memory（零依赖演示）/ milvus（需装 pymilvus + 启动服务）
    vector_store_type: str = "pgvector"
    milvus_host: str = "localhost"
    milvus_port: int = 19530

    # Elasticsearch（M5-8 复合检索 + 未来搜索索引）：ES 负责工业级 BM25 关键词召回，
    # 与 pgvector 向量路做 RRF 融合；PG 仍是分块权威存储。向量召回仍走 pgvector（方案 A：
    # ES dense_vector 维度需固定、本项目 embedding 变维度，故向量不迁 ES）。
    # ES 启用需 docker-compose 启动 xiongda-elasticsearch 服务，并安装 elasticsearch 依赖。
    elasticsearch_enabled: bool = False  # M5-8 总开关；关则完全走旧 pgvector+自研 BM25，零风险回退
    elasticsearch_hosts: str = Field(
        default="http://localhost:9200",
        validation_alias=AliasChoices("ES_HOSTS", "ELASTICSEARCH_HOSTS"),
    )  # ES 地址（逗号分隔多节点）
    elasticsearch_user: str = Field(default="", validation_alias=AliasChoices("ES_USER", "ELASTICSEARCH_USER"))
    elasticsearch_password: str = Field(default="", validation_alias=AliasChoices("ES_PASSWORD", "ELASTICSEARCH_PASSWORD"))
    elasticsearch_api_key: str = Field(default="", validation_alias=AliasChoices("ES_API_KEY", "ELASTICSEARCH_API_KEY"))
    elasticsearch_index_prefix: str = "xiongda"  # ES 索引名前缀（每租户一索引 {prefix}_{tenant_id}）
    elasticsearch_request_timeout: float = 10.0
    # 兼容模式：True 时 8.x 客户端以「compatible-with=7」header 连接 7.x 服务器
    # （当前本地方案为 Elasticsearch 7.17，故默认 True）。若改连 8.x 服务器则设 False。
    elasticsearch_compat_7: bool = True
    elasticsearch_auto_reindex_on_empty: bool = False  # 启动时为「PG 有数据但 ES 索引缺失」的租户自动补迁
    retrieval_es_keyword_topk: int = 20  # ES BM25 召回数
    # content 字段分词器：默认 standard（中英文可用）；服务器装 IK 插件后可设 "ik_max_word"
    # 以按中文词粒度分词，显著提升中文 BM25 召回质量。未装 IK 时切勿设 ik_max_word，否则建索引报错。
    elasticsearch_text_analyzer: str = "standard"
    # ES BM25 _score 归一化基准：ES _score 与自研 BM25 量纲不同，除以批次内 max 再乘此值，
    # 使其最佳命中≈自研 BM25 量级，保证 _merge_vector_dominant 的 BM25 补充门槛行为一致。
    elasticsearch_bm25_scale: float = 5.0

    # Postgres（向量持久化存储，默认与 Java 后端同源；可用 .env 覆盖）
    pg_host: str = "localhost"
    pg_port: int = 5432
    pg_user: str = "postgres"
    pg_password: str = "postgres123!@#"
    pg_db: str = "xiongda"

    # Chunking
    chunk_size: int = 512
    chunk_overlap: int = 50

    # M5-5 父子分块（parent/child chunk, small-to-big retrieval）：
    # 子块（chunk_size）进向量索引，检索语义聚焦、精度高；父块（parent_chunk_size）
    # 入库但不进向量索引，检索命中子块后回溯其父块内容拼上下文，供 LLM 获得更完整连贯的语境。
    enable_parent_child: bool = True         # 分块时额外产出父块并建立子→父归属
    parent_chunk_size: int = 1500            # 父块大小（远大于子块，承载完整上下文）
    parent_chunk_overlap: int = 100          # 父块间重叠
    retrieval_parent_context: bool = True    # 检索命中子块后回溯父块内容填入 parent_content

    # 检索相关性门槛：检索结果均不相关时视为「无相关文档」，不向下游推送引用来源
    # （避免用户问知识库没有的内容时，AI 仍展示不相关的错误引用来源）。
    retrieval_relevance_gate: bool = True       # 总开关
    retrieval_vector_min_relevance: float = 0.30  # 向量余弦相似度门槛（无 rerank 时使用）
    retrieval_bm25_min_relevance: float = 1.0     # BM25 分数门槛（关键词强相关时放宽向量门槛）
    retrieval_rerank_min_relevance: float = 0.40  # Rerank/LLM 重排相关性分数门槛（0~1，低于此的跨主题块剔除）
    retrieval_vector_dominant: bool = True        # 向量可用时以语义检索为主，BM25 仅补充（抑制关键词噪声）
    retrieval_relative_ratio: float = 0.80        # 相对相关性阈值：剔除与最优分差距超过此比例的跨主题噪声
    retrieval_max_chunks_per_doc: int = 5         # 单文档最多进入 top-N 的块数（避免单文档刷屏；设为 top_n 即不限制）
    retrieval_exclude_qa_blocks: bool = True      # 排除 QA 增强块（source 通常为空，不作为引用来源，与 get_original_chunks 一致）
    # M5-7：增强块（qa/question/summary/wiki/entity）由 LLM 生成、source 通常空/非原文，仅用于
    # 提升检索召回的语义桥接，不作为引用来源（与 retrieval_exclude_qa_blocks 同语义，现已并入此开关）。
    retrieval_exclude_augment_blocks: bool = True
    # 语义平手时由词法重合度决胜：向量分与最优分差距 <= retrieval_bm25_tie_epsilon 视为平手，
    # 若块内容与查询的 bigram 重合度 >= retrieval_bm25_tie_overlap_min，则按重合度 * boost 加权
    # 抬升，让「后端规范」类关键词相关块优先于向量模型略偏的前端/JavaWeb 块。差距明显
    # （如 0.565 vs 0.493）时不触发，保证向量主导对「后端刚入职」类问题的收敛效果不被噪声破坏。
    # 注：用块内容与查询的词法重合度而非 BM25 物理块匹配，因 BM25 命中的常是 QA 增强块，
    # 与原文块 key 不同，直接词法比对才能正确识别含查询关键词的原文块。
    retrieval_bm25_tie_epsilon: float = 0.02      # 语义平手阈值（向量分差）
    retrieval_bm25_tie_boost: float = 0.10        # 平手且词法命中时，重合度乘此权重作为抬升量
    retrieval_bm25_tie_overlap_min: float = 0.25  # 触发平手决胜的最小词法重合度
    retrieval_bm25_tie_top_n: int = 10            # 预留：参与平手决胜的 BM25 候选条数（当前未使用）

    # 重排方式（对标业界成熟方案 Rerank 跨领域判别意图，但用已配置的 LLM 代替写死的领域词 / OpenAI rerank endpoint）：
    # - llm（默认）：用已配置的 LLM 对候选块逐块打 0~1 相关性分并重排，跨领域判别最稳、不写死领域，
    #   且不新增服务 / 密钥（复用 AI 配置页已填的 LLM）。
    # - api：调用 OpenAI 兼容 /rerank 接口（需配置 rerank_api_key；火山原生 rerank 非此格式，不可用）。
    # - none：不做重排，直接截断 top_n。
    retrieval_rerank_method: str = "llm"
    retrieval_rerank_top_k: int = 10  # LLM/API 重排前的融合候选池大小：融合时先取更大池（>top_n），
                                     # 重排再精排取 top_n，提升模糊问句召回（真正相关块未必进前 top_n），
                                     # 又不带回跨领域噪声。关闭重排（method=none）时不起作用。

    # 方案C 大纲意图召回：问「架构/大纲/目录/考点」类问题时，优先召回章节标题
    # outline 块的数量（保送到结果前部，使 LLM 能基于真实章节主题归纳知识框架）。
    retrieval_outline_top_n: int = 12
                                     # 重排再精排取 top_n，提升模糊问句召回（真正相关块未必进前 top_n），
                                     # 又不带回跨领域噪声。关闭重排（method=none）时不起作用。

    # 普通问答增强（对齐 业界 KnowledgeQA 方案 的 query rewrite + query expansion）：
    # 仅作用于 rag 模式（retrieve(enhance=True) 时触发），Agent 模式不开启（Agent 内部
    # 由 LLM 自生成子查询，不做二次改写，避免画蛇添足）。
    enable_query_rewrite: bool = True    # 检索前用 LLM 把口语化问题改写成检索友好 query
    enable_query_expansion: bool = True  # 主检索召回不足时用 LLM 生成扩展查询再检索并 RRF 合并
    retrieval_expansion_min: int = 3     # 主检索结果数 < 此值才触发 expansion（避免无谓额外调用）

    # 检索无结果兜底策略（对标业界成熟方案）：知识库/文档为空或检索不匹配时
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
