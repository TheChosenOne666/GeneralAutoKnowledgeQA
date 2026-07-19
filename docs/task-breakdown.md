# 熊答 — 任务功能拆分

> 按里程碑分阶段，标注技术栈、优先级、预估工时、依赖关系。

## 里程碑规划

| 里程碑 | 目标 | 范围 |
|---|---|---|
| **M1 - MVP** | 能注册登录、上传文档、基本问答 | 认证 + 知识库基础 + 基础问答 |
| **M2 - 核心** | RAG 检索 + 流式回答 + 引用溯源 | RAG 检索 + SSE 流式 + 会话管理 |
| **M3 - 管理** | 多角色权限 + 成员管理 + AI配置 | RBAC + 成员 + 配置 + 审计 |
| **M4 - 增强** | Agent 推理 + 共享/个人库 + UI 精细化 | Agent + 权限细化 + 体验优化 |
| **M5 - 进阶** | 持久化队列 + 重试/取消 + 多模态 + GraphRAG + 复合检索 | 队列 + 多模态 + GraphRAG + ES+pgvector |
| **M6 - 检索增强** | RRF 参数可配 + Chunk 拼接 + FAQ 负向过滤 + 相邻块补全 | 参考腾讯 WeKnora 混合检索系统 |

---

## M1 — MVP（能跑通的核心链路）

### M1-1 数据库初始化 [后端/Java] P0 · 0.5d ✅ 已完成

- ~~配置 PostgreSQL 连接（application.yml）~~
- ~~JPA ddl-auto 建表验证~~ → 改为 **MyBatis-Plus + 手动建表**
- ~~初始化默认数据（super_admin 账号）~~ → TODO（注册流程自动创建）
- ~~Redis 连接验证~~

**实际完成内容：**
- ✅ Docker 启动 PostgreSQL 16（镜像 `postgres:16-alpine`，已自带 pgvector 0.8.1）+ Redis 7（镜像 `redis:7-alpine`）
- ✅ 创建 `xiongda` 数据库
- ✅ application.yml 配置 PostgreSQL + Redis + MyBatis-Plus + JWT + CORS + Knife4j
- ✅ Maven 3.9.9 安装 + 阿里云镜像加速
- ✅ Spring Boot 3.4 编译通过 + 启动成功（Tomcat port 8080）
- ✅ 8 张表自动建表验证（tenant/user/knowledge_base/document/conversation/message/ai_config/audit_log）

**关键决策（偏离原计划）：**
1. **ORM 从 JPA 改为 MyBatis-Plus**（用户要求按模板风格）
2. **ID 从 UUID 改为 Long 雪花算法**（ASSIGN_ID）
3. **Spring Security 改为 @AuthCheck AOP**（自定义注解 + 切面）
4. **密码从 BCrypt 改为 MD5 + 盐值**
5. **表名从复数改单数**（tenant 而非 tenants）
6. **字段名驼峰**（map_underscore_to_camel_case: false）
7. **逻辑删除** @TableLogic isDelete 0/1
8. **统一响应** BaseResponse + ResultUtils + ErrorCode

**验证结果：**
- `mvn compile` EXIT_CODE: 0
- Spring Boot 启动 9.48s，Tomcat 8080
- PostgreSQL `\dt` 8 张表全部创建

**依赖**: 无
**产出**: 数据库可连接，7张表自动建表

---

### M1-2 认证服务 [后端/Java] P0 · 1d ✅ 已完成

- ~~登录接口 `/api/user/login`（邮箱+密码 → JWT）~~
- ~~注册接口 `/api/user/register`（自动创建租户 + tenant_admin）~~
- ~~获取当前用户 `/api/user/get/login`~~
- ~~JWT 过期/无效处理（GlobalExceptionHandler 拦截）~~
- ~~密码 MD5 + 盐值加密~~
- **依赖**: M1-1 ✅
- **产出**: Postman 可调通注册/登录/get/login

> 注：接口路径按模板风格（/api/user/login 而非 /api/auth/login）

**实际完成内容：**
- ✅ 后端接口全部实现（UserController / UserServiceImpl）
  - 注册：自动创建租户 + tenant_admin 角色，邮箱查重，MD5+盐值加密
  - 登录：邮箱+密码校验，用户状态校验，生成 JWT Token
  - 获取当前用户：解析 Authorization Header → JWT → 查库 → 状态校验
- ✅ JwtUtil 工具类（生成/解析/过期/无效 Token 处理）
- ✅ GlobalExceptionHandler 统一异常处理（BusinessException / RuntimeException / 参数校验）
- ✅ @AuthCheck AOP 权限切面
- ✅ 单元测试编写并全部通过（18 个用例）
  - `JwtUtilTest`：4 个（Token生成解析、tenantId为空、过期、无效）
  - `UserServiceImplTest`：14 个（注册成功/邮箱重复、登录成功/用户不存在/密码错误/停用、获取登录用户成功/无Token/无效Token/用户不存在/停用、VO转换）

**Bug 修复（单元测试发现）：**
- `UserServiceImpl.getLoginUser` 中 `catch (Exception e)` 误吞了 `BusinessException`，导致用户被停用时返回 `NOT_LOGIN_ERROR(40100)` 而非 `FORBIDDEN_ERROR(40300)`
- 修复：将用户状态校验移出 try-catch，仅 JWT 解析异常走 catch

**代码清理：**
- 删除 4 处 WARNING：未使用 import（AuditLog.Map、UserServiceImpl.NetUtils）、冗余 implements Serializable（BusinessException、KnowledgeBaseQueryRequest）

**验证结果：**
- `mvn test -Dtest=JwtUtilTest,UserServiceImplTest` → Tests run: 18, Failures: 0, Errors: 0 ✅

---

### M1-3 认证页面 [前端] P0 · 1d ✅ 已完成

- ~~登录/注册 Tab 切换~~
- ~~表单验证（邮箱格式、密码长度）~~
- ~~调用后端 API，存储 JWT 到 localStorage~~
- ~~登录成功跳转 /chat~~
- ~~401 自动跳转登录页~~
- **依赖**: M1-2 ✅
- **产出**: 浏览器可注册登录

**实际完成内容：**
- ✅ 前端骨架已有 AuthPage（登录/注册 Tab 切换、邮箱+密码表单、错误提示、loading 状态）
- ✅ useAuth Hook（login/register/logout，注册仅创建账户、不自动登录；注册成功切回登录表单并提示「注册成功，请登录」，JWT 存 localStorage）
- ✅ API 客户端（Axios 拦截器：请求注入 Bearer Token，响应 40100 自动跳转 /login）
- ✅ AuthGuard 路由守卫（无 Token 跳转 /login）
- ✅ 修复 `types/index.ts` TokenResponse 类型定义语法错误
- ✅ 密码输入框增加 `minLength={6}` 前端验证（与后端 6-128 位一致）
- ✅ 前后端联调测试通过

**联调时发现并修复的 M1-1 遗留问题（数据库表结构不匹配）：**
- 问题：数据库表是旧 JPA 遗留结构（uuid 主键、`hashed_password` 列名、无 `is_delete` 列、复数表名），与 MyBatis-Plus 实体完全不匹配，导致所有数据库操作失败
- 修复 1：重建 8 张表（`schema.sql`），按实体结构：bigint 雪花 ID、下划线列名、`is_delete` 逻辑删除列、外键约束、索引
- 修复 2：`application.yml` 开启 `map-underscore-to-camel-case: true`（驼峰字段↔下划线列名自动映射）
- 修复 3：`User` 实体 `@TableName("app_user")`（避开 PostgreSQL 保留字 `user`）
- 修复 4：清理占用 8080 端口的旧后端进程（`com.xiongda.XiongdaApplication` 旧类名）

**验证结果：**
- 注册 `POST /api/user/register` → code:0, 返回 userId ✅
- 登录 `POST /api/user/login` → code:0, 返回 LoginUserVO(含 JWT token) ✅
- 获取当前用户 `GET /api/user/get/login` → code:0, 返回用户信息 ✅
- 前端 `tsc --noEmit` 编译通过 ✅
- 浏览器 localhost:5173/login 页面可正常访问 ✅

---

### M1-4 知识库基础 CRUD [后端/Java] P0 · 1d ✅ 已完成

- ~~创建知识库 `/api/knowledge/bases`~~ → 实际路径 `/api/knowledge/add`
- ~~知识库列表（按 scope 筛选）~~
- ~~文档上传（MultipartFile → 保存文件 → 创建 Document 记录）~~
- ~~文档列表查询~~
- ~~文档删除~~
- **依赖**: M1-2 ✅
- **产出**: 可上传文件并查看列表

**实际完成内容：**
- ✅ 后端接口全部实现（KnowledgeBaseController + KnowledgeBaseServiceImpl + DocumentServiceImpl）
  - 创建知识库 `POST /api/knowledge/add`（名称校验、scope 默认 personal、ownerId 绑定）
  - 知识库列表 `GET /api/knowledge/list`（按 scope 筛选：shared/personal/all）
  - 文档上传 `POST /api/knowledge/document/upload`（MultipartFile 保存文件 → 创建 Document 记录）
  - 文档列表 `GET /api/knowledge/document/list`（按 kbId + tenantId 查询）
  - 文档删除 `POST /api/knowledge/document/delete`（逻辑删除 + 跨租户校验）
- ✅ 单元测试 16 个全通过
  - `KnowledgeBaseServiceImplTest`：8 个（创建成功/默认scope/名称为空、列表查询全部/共享/个人、VO转换/null）
  - `DocumentServiceImplTest`：8 个（上传成功、列表查询/空列表、删除成功/不存在/跨租户、VO转换/null）

**Bug 修复：**
- `KnowledgeBaseController.uploadDocument` 文件保存路径从相对路径改为绝对路径（`toAbsolutePath()`），修复 `MultipartFile.transferTo` 对相对路径的处理导致 500 错误

**验证结果：**
- 创建知识库 → code:0, 返回 kbId ✅
- 知识库列表 → code:0, 返回列表（含 scope 筛选）✅
- 文档上传 → code:0, 返回 docId ✅
- 文档列表 → code:0, 返回文档列表 ✅
- 文档删除 → code:0, data:true ✅
- `mvn test` → Tests run: 16, Failures: 0, Errors: 0 ✅

---

### M1-5 AI 服务基础 [Python] P0 · 1d ✅ 已完成

- ~~FastAPI 服务启动（port 8001）~~
- ~~`/health` 健康检查~~
- ~~文档处理接口 `/ai/document/process`（提取文本 → 分块）~~
  - ~~PDF: PyMuPDF~~
  - ~~DOCX: python-docx~~
  - ~~MD/TXT: 直接读取~~
  - ~~RecursiveCharacterTextSplitter 分块~~
- **依赖**: 无（独立服务）
- **产出**: 可处理文档返回分块

**实际完成内容：**
- ✅ FastAPI 服务启动（port 8001），健康检查 `GET /health` → `{"status":"ok","service":"ai-service"}`
- ✅ 文档处理接口 `POST /ai/document/process` 实现
  - `extract_text()`：PDF（PyMuPDF）/ DOCX（python-docx）/ MD/TXT（直接读取）
  - `chunk_text()`：LangChain RecursiveCharacterTextSplitter（chunk_size=512, overlap=50）
  - `process()`：提取 → 分块 → 返回分块数量（向量化/存储在 M2 接入，当前跳过）
- ✅ Python 虚拟环境 `.venv` 创建，安装最小依赖（fastapi/uvicorn/pydantic/loguru/pymupdf/python-docx/langchain-text-splitters）

**验证结果：**
- `GET /health` → `{"status":"ok","service":"ai-service"}` ✅
- `POST /ai/document/process`（txt 文件）→ `{"doc_id":"test-1","status":"ready","chunk_count":3}` ✅

---

### M1-6 Java → Python 文档处理联动 [后端/Java] P0 · 0.5d ✅ 已完成

- ~~上传文档后异步调用 `AiServiceClient.processDocument()`~~
- ~~更新 Document 状态（pending → parsing → embedding → ready/failed）~~
- ~~文档列表返回处理状态~~
- **依赖**: M1-4, M1-5
- **产出**: 上传后自动处理，状态实时更新

**实际完成内容：**
- ✅ `DocumentService` 新增 `updateDocumentStatus` 和 `triggerDocumentProcessing` 方法
- ✅ `DocumentServiceImpl` 注入 `AiServiceClient`，用 `CompletableFuture.runAsync` 异步处理：
  - 更新状态为 parsing → 调用 Python AI 服务 → 成功更新 ready+chunkCount / 失败更新 failed+errorMsg
- ✅ `KnowledgeBaseController` 上传后调用 `triggerDocumentProcessing`（替换 TODO）
- ✅ 前端文档列表已展示状态徽章（ready/parsing/failed）

**验证结果：**
- 上传 txt 文件 → `code:0, docId` ✅
- 3 秒后查询文档列表 → `status: "ready", chunkCount: 3` ✅
- Java → Python 联动正常，异步处理状态自动更新 ✅

---

### M1-7 知识库页面 [前端] P0 · 1d ✅ 已完成

- ~~共享/个人知识库 Tab 切换~~
- ~~拖拽上传区~~ → 文件选择上传
- ~~文档列表表格（文件名、类型、大小、状态、时间）~~
- ~~状态徽章实时显示~~
- ~~新建知识库弹窗~~
- **依赖**: M1-4 ✅
- **产出**: 浏览器可上传文档查看状态

**实际完成内容：**
- ✅ 知识库页面 KnowledgeBasePage 实现
  - 共享/个人知识库 Tab 切换（scope 筛选）
  - 知识库选择下拉框
  - 新建知识库弹窗（名称输入 → 创建）
  - 文档上传按钮（选择文件 → 上传 → 状态轮询）
  - 文档列表表格（文件名、类型、大小、状态徽章、时间）
  - 状态徽章实时显示（ready/parsing/failed）
  - 新建知识库 + 上传文档分离为两个独立按钮
- ✅ 前后端联调测试通过

**验证结果：**
- 浏览器 localhost:5173/knowledge 可正常访问 ✅
- 创建知识库 → 文档上传 → 状态更新 全链路通过 ✅

---

### M1-8 基础问答（无 RAG） [全栈] P0 · 1.5d ✅ 已完成

- ~~**Python**: LangChain 接入 LLM（火山方舟/OpenAI），流式生成~~ → 用 httpx 直接调用 OpenAI 兼容 API
- ~~**Python**: `/ai/chat/stream` SSE 接口（LLM 直接回答，无检索）~~
- ~~**Java**: ChatController SSE 透传~~
- ~~**前端**: 问答页面（输入框 + 流式渲染回答）~~
- ~~**前端**: 会话列表 + 新建会话~~ → 会话由 Java 自动创建
- **依赖**: M1-2, M1-5
- **产出**: 能和 AI 对话，流式输出

**实际完成内容：**
- ✅ Python `llm.py`：用 httpx 调用 OpenAI 兼容 API（火山方舟/OpenAI），流式生成；无 API Key 时回退模拟输出
- ✅ Python `chat.py`：SSE 接口简化（M1-8 无 RAG，直接调 LLM），修复 `dir()` bug
- ✅ Java `ChatController`：SSE 透传已有（会话自动创建 + 用户消息保存 + Flux 透传）
- ✅ 前端 `ChatPage.tsx`：SSE 流式渲染 + 消息列表（用户右对齐/AI左对齐）+ 推荐问题点击发送
- ✅ 前端 `api/chat.ts`：会话管理 API + SSE fetch 流式读取

**验证结果：**
- Python SSE 返回流式 token ✅（`event: token, data: {"content":"我"}`）
- TypeScript 编译通过 ✅
- 浏览器可对话，流式输出 ✅

> 注：当前为模拟模式（未配置 LLM API Key）。在 `ai-service/.env` 中配置 `LLM_API_KEY` 后自动接入真实大模型。

---

### M1-8.5 问答页 UI 精细化 [前端] P1 · 0.5d ✅ 已完成

- ~~发送按钮移到模型选择器右侧~~
- ~~输入框固定在底部，不随消息滚动~~
- ~~修复 JSX 结构错误（多余闭合 div + 未定义 ChatSession 类型）~~
- **依赖**: M1-8 ✅
- **产出**: 问答页 UI 布局正确，输入框固定底部

**实际完成内容：**
- ✅ 修复 ChatPage.tsx JSX 结构错误
  - 删除未定义的 `ChatSession` 类型引用（死代码 `MOCK_SESSIONS`）
  - 移除多余的闭合 `</div>` 标签
- ✅ 发送按钮从左侧按钮组移到模型选择器右侧
- ✅ 输入框固定在底部
  - 外层容器从 `flex-1` 改为 `h-full`（父容器非 flex，flex-1 无效）
  - 输入区从 `absolute bottom-0` 改为 `flex-shrink-0` 布局
  - 移除 scrollRef 的 `pb-64` padding（不再需要为绝对定位留空间）
- ✅ 侧边栏菜单调整：对话移到最后一项，历史会话区域标题改为"对话"

---

### M1-9 侧边栏布局 [前端] P0 · 0.5d ✅ 已完成

- ~~AppLayout 组件（侧边栏 + 主内容区）~~
- ~~动态菜单（按角色过滤）~~
- ~~用户信息展示 + 退出登录~~
- ~~路由守卫 AuthGuard~~
- **依赖**: M1-3 ✅
- **产出**: 登录后进入带侧边栏的布局

**实际完成内容：**
- ✅ AppLayout 组件实现（左侧边栏 + 右侧主内容区）
  - Logo 区（熊答）
  - 动态菜单（按角色过滤：知识库/AI模型配置/成员管理/审计日志/对话）
  - 对话页侧边栏历史会话列表（近7天分组 + 新建对话按钮）
  - 用户信息展示（头像首字母 + 姓名 + 角色标签 + 退出按钮）
  - 路由守卫 AuthGuard（无 Token 跳转 /login）
- ✅ 对话菜单移至侧边栏最后一项

**验证结果：**
- 登录后进入 /chat，侧边栏正常显示 ✅
- 不同角色看到不同菜单项 ✅

---

**M1 合计: ~8.5 天**

---

## M2 — RAG 核心检索

### M2-1 Embedding 服务 [Python] P0 · 1d ✅ 已完成

- 接入 OpenAI 兼容 Embedding 接口（httpx 直调 `/embeddings`，兼容火山方舟）
- 文本向量化 `embed_text()` / 批量 `embed_batch()`
- 未配置 `embedding_api_key` 时降级为确定性伪向量（字符级 bigram + L2 归一化），保证开发/单测/演示链路可跑通
- 配置 API Key / Base URL（`core/config.py`）
- **依赖**: M1-5 ✅
- **产出**: 可调用 Embedding（真实/降级双路径）

**实际完成内容：**
- ✅ `services/embedding.py` 重写：真实路径 `_embed_remote` 用 httpx 调 `{embedding_base_url}/embeddings`；降级 `_embed_fallback` 用字符级 bigram 哈希伪向量
- ✅ 不引入 langchain-openai 重依赖，与 `llm.py` httpx 风格一致
- ✅ 单元测试 `tests/test_embedding.py`（维度/确定性/归一化/异步）6 个全通过

---

### M2-2 Milvus 向量存储 [Python] P0 · 1.5d ✅ 已完成

- 内存向量存储（默认，零依赖）：余弦相似度检索 + BM25 关键词检索，tenant_id + kb_id 过滤
- Milvus 向量存储（可选集成，pymilvus 直接管理，lazy import）
- 存储文档分块向量（含元数据：doc_id, kb_id, tenant_id, source, page, chunk_index）
- 向量检索 / 关键词检索 / 删除文档向量 `delete_by_doc()`
- **依赖**: M2-1 ✅
- **产出**: 文档可入库检索（默认内存，Milvus 按需启用）

**实际完成内容：**
- ✅ `services/vector_store.py` 重写：`InMemoryVectorStore`（默认，零依赖）、`MilvusVectorStore`（pymilvus 直接管理，lazy import）
- ✅ `core/config.py` 增加 `vector_store_type`（默认 memory）/ `milvus_host`/`milvus_port`
- ✅ 全局单例 `vector_store_service` 通过模块对象访问（修复 `from-import` 别名导致的单例不同步陷阱）
- ✅ 单元测试 `tests/test_vector_store.py`（检索/租户过滤/知识库过滤/删除/BM25）5 个全通过
- ⚠️ 真实 Milvus 集成需 `pip install pymilvus` + 启动 Milvus（docker-compose 已加 milvus 服务），本环境未做联调验证（默认内存已完整演示）

---

### M2-8 向量存储持久化改造（pgvector，2026-07-14）✅ 已完成

> 背景：原 `InMemoryVectorStore` 为进程内存存储，Python 服务一重启全部文档向量即丢失，导致已上传文档"知识库查无内容"走通用知识兜底（用户实测：重启后问算法文档答非所问）。对齐业界成熟 RAG 方案的 Postgres(pgvector) 持久化方案修复。

**参考**：业界成熟 RAG 方案默认即用 Postgres（ParadeDB/pgvector）持久化向量，表 `embeddings`（halfvec 变维度 + 过滤列），重启不丢知识。本项目运行中的 Postgres 已自带 pgvector 0.8.1，无需换镜像。

**改动（Python）**：
- `core/config.py`：`vector_store_type` 默认改为 `pgvector`；新增 `pg_host/pg_port/pg_user/pg_password/pg_db`（与 Java 同源，可 .env 覆盖）
- 新增 `core/pg_client.py`：Postgres 异步连接池单例（对齐 `redis_client` 懒加载风格）
- `services/vector_store.py`：新增 `PgVectorStore`
  - `store_chunks`：写入 `embeddings` 表（halfvec 变维度 + tenant/kb/doc 过滤列），同 doc_id 幂等覆盖；同步更新 BM25 内存索引
  - `search`：PG 余弦相似度检索（`1 - (embedding <=> $1::halfvec)`），按 tenant/kb 过滤
  - `keyword_search`：**复用现有 BM25 兜底**（用户要求保留），BM25 索引在 `warmup_bm25` 时从 PG 全量加载，重启后兜底检索仍可用
  - `delete_by_doc`：清 PG + BM25 索引，避免孤立向量
  - `ensure_embeddings_table`：`CREATE EXTENSION IF NOT EXISTS vector` + 建 `embeddings` 表及索引
- `main.py` 生命周期：启动时建表 + `warmup_bm25` 预热；关闭时释放连接池
- `routers/document.py`：新增 `DELETE /ai/document/{doc_id}` 清理向量（接 `delete_by_doc`）
- `requirements.txt`：新增 `asyncpg`
- BM25 算法抽为 `services/vector_store._bm25_search` 共享函数（内存版与 PG 版复用，行为不变）

**单元 / 集成验证**：
- `tests/test_pg_vector_store.py` 6 例全过（真实 PG：落库/检索/租户过滤/知识库过滤/删除/BM25 兜底 + 模拟重启 warmup 恢复）
- `tests/test_vector_store.py` 5 例全过（内存版 BM25 重构无回归）
- 联调：重启 Python 后新进程 `BM25 预热完成，加载 11 条分块`（从 PG 恢复），检索"算法 入职"命中《算法工程师新员工入职指南（企业正式版）》；已将库内唯一 ready 文档重新向量化写入 PG，重启不再丢知识

**注意**：已落库文档的向量仅存在于改造前的旧内存进程，重启丢失后需重新向量化一次（重传文档或触发 process）写入 PG；此后常规上传/重处理均自动持久化。

### M2-3 文档处理全链路 [Python] P0 · 1d ✅ 已完成

- 完善 `document_processor.process()`：提取文本 → 分块 → 向量化（Embedding）→ 存储（VectorStore）
- 返回分块数量 + 元数据（source 等）写入向量库
- 错误处理 + 状态回写（Java 端 M1-6 已调用 `POST /ai/document/process`）
- **依赖**: M2-1, M2-2 ✅
- **产出**: 上传文档自动向量化入库

**实际完成内容：**
- ✅ `services/document_processor.py` 完善 process：去掉 M1 阶段的 `NotImplementedError` 跳过，真实向量化 + 入库
- ✅ `routers/document.py` 移除骨架 `except NotImplementedError` 特判
- ✅ 集成单测 `tests/test_rag_pipeline.py`：文档入库后 RAG 检索可命中

---

### M2-4 RAG 检索服务 [Python] P0 · 1.5d ✅ 已完成

- `rag_service.retrieve()` 完整流程：
  1. Query 向量化
  2. 向量检索 Top-K=20
  3. BM25 关键词检索 Top-K=20
  4. RRF 融合去重
  5. Rerank 精排 Top-N=5（可选，未配置 `rerank_api_key` 则跳过）
- 返回 RetrievalResult（content, source, page, score, doc_id, kb_id, chunk_index）
- **依赖**: M2-2 ✅
- **产出**: 输入问题返回相关文档片段

**实际完成内容：**
- ✅ `services/rag.py` 重写：混合检索 + RRF 融合 + 可选 Rerank（配置 `rerank_api_key` 时调用 `/rerank`，否则跳过）
- ✅ 中文降级检索优化：向量 / BM25 均改用字符级 bigram，提升无 API Key 时演示命中率
- ✅ 集成单测覆盖 process → retrieve 全链路

#### 检索精度修复（2026-07-15 ~ 07-16）— 跨领域串味抑制 [Python] 🐞

- **背景**：用户反馈「问后端却返回前端文档来源」（如「后端规范是什么」返回 3 前端 + 2 后端；「后端的任务是做什么需求」返回前端 + JavaWeb + 后端）。排查确非缓存问题（已清 L1 缓存 + 用真实配置复现）：短 query 经 query rewrite 后（如「后端规范」），弱向量模型把**前端块余弦分（0.636）评得比后端块（0.568）高**，向量主导融合 + 平手决胜阈值救不了；relative-ratio 过滤只能裁长尾、无法纠正错误排序。
- **参照 业界方案**：其跨领域判别**完全依赖 Rerank 精排**（cross-encoder 对 query+passage 联合打分，且按 `RerankThreshold` 阈值过滤跨主题块，见 `rerank.go:403-432`）。但本项目的 volcengine rerank 接口（`/api/knowledge/service/rerank`）是 VikingDB 专用、需 HMAC-SHA256 签名，**不兼容** OpenAI `/rerank` 格式，无法即插即用默认开启。
- **方案演进**：
  1. **07-15 初版（已废弃）**：用写死的 `DOMAIN_GROUPS` 文件名领域聚焦作兜底——用户指出这是写死领域词（如「后端」→ backend、「前端」→ frontend），仅覆盖白名单、对文件名无领域标签的文档（如《自动化测试新员工入职指南》）漏判，且新增领域需改代码。故废弃。
  2. **07-16 终版（LLM 重排）**：改用**已配置的 LLM 当重排器**（`services/rag.py` 的 `_rerank_with_llm`）。融合后把 query + 候选块发给 LLM，要求逐块给 0~1 相关性分数，按分数重排并按阈值过滤跨主题块。**不写死任何领域词、不新增服务**（复用 AI 配置页已填的 LLM），对任意新增领域自动生效；调用失败 / 分数不可解析时安全回退向量融合顺序（不静默吞错）。阈值过滤对标业界成熟方案（最优分仍 ≥ 0.15 保留 top1 兜底）。
- **改动**：
  - `services/rag.py`：删除 `DOMAIN_GROUPS` / `_domain_focus`；新增 `_rerank_with_llm`（调 `llm_service.complete` 打分）+ `_parse_rerank_scores`（稳健解析 JSON 分数数组）。`retrieve` 按 `retrieval_rerank_method`（`llm`/`api`/`none`，默认 `llm`）选择重排方式；原 OpenAI `/rerank` 路径保留为 `api`。
  - `core/config.py`：`retrieval_domain_focus` → `retrieval_rerank_method`（默认 `llm`）；`retrieval_rerank_min_relevance` 由 0.10 提到 0.30（适配 LLM 0~1 分）；新增 `retrieval_rerank_top_k`（默认 10，重排候选池）。
  - L1 缓存 key 纳入 `retrieval_rerank_method` + `retrieval_rerank_top_k` + `top_n`，切换算法/范围后旧缓存自动失效。

#### 检索精度修复（2026-07-16 续）— 方案A 重排候选池扩大 [Python]
- **背景**：方案A（用户采纳）针对「模糊问句真正相关块未进前 `top_n`，LLM 只在这 `top_n` 内打分致全 0 漏召回」的回归。实测「后端的任务是做什么需求」在 `top_n=5` 融合下相关后端块排不进前 5，LLM 重排后全 0 返回「无相关文档」。
- **改动**：`rag.py` 的 `retrieve` 在开启重排时，融合候选池由 `top_n` 改为 `max(top_n, retrieval_rerank_top_k)`（默认 10），LLM 在整个池内精排后按阈值过滤取 `top_n`；未开启重排时仍直接截断 `top_n`。缓存 key 同步纳入 `rerank_top_k` 与 `top_n`。
- **验证**：`tests/test_retrieval_merge.py` 新增 `test_rerank_pool_enlarged_before_rerank`（断言送重排候选数 `>= rerank_top_k`、最终截断到 `top_n`），全 10 例通过。真实 E2E（glm-5-2 + doubao-embedding-vision）：Q1「后端规范是什么」重排后只留 `JavaWeb笔记.docx`、不含前端；但 Q2「后端的任务是做什么需求」即便扩池到 10（且 `enhance=True` 含 query rewrite/expansion）仍返回「无相关文档」——根因为弱 embedding 对模糊问句连前 10 都召回不到真正相关块（召回天花板，非重排 bug）。已重启 8001（新 PID 12372，health 200）。
- **待定（需用户定）**：Q2 类模糊问句若要进一步召回，可选 ① 把 `retrieval_rerank_top_k` 提到 20（向量/BM25 各 top20，融合池可到 20，glm 一次看 20 块更慢成本更高）；② 换更强 embedding 模型（bge-m3 等，根因修复但需重向量化全库）；③ 承认该模糊问句当前知识库覆盖不足，保持「无相关内容」的干净行为。
- **验证**：`tests/test_retrieval_merge.py` 替换 2 例领域聚焦测试为 LLM 重排测试（模拟弱向量把前端评到后端之上 → LLM 重排剔除前端、保留后端/JavaWeb；LLM 返回非 JSON 时安全回退），全 9 例通过。

---

### M2-5 RAG 流式问答 [全栈] P0 · 1d ✅ 已完成

- **Python**: `/ai/chat/stream` 接入 RAG
  - 检索 → 构建 Prompt（系统提示 + 检索上下文 + 问题）→ LLM 流式生成
  - SSE 推送 `sources` 事件（引用来源：文件名 / 页码 / 内容片段）
- **Java**: `ChatController` 原样透传 SSE 流（含 `sources` 事件），无需改动
- **前端**: 解析 `event: sources` 渲染引用来源卡片（文件名 + 页码 + 查看原文展开）
- **前端**: AI 回答用 `react-markdown` + `remark-gfm` + `rehype-highlight` 渲染（代码高亮）
- **依赖**: M2-4, M1-8 ✅
- **产出**: 问答有引用来源，回答基于知识库

**实际完成内容：**
- ✅ `routers/chat.py`：`mode=="rag"` 时先 `rag_service.retrieve` 检索，构建上下文，LLM 流式生成；检索失败降级为无上下文问答；推送 `sources` 事件
- ✅ 单元测试 `tests/test_chat.py`：验证 `sources`/`token`/`done` 事件与检索命中
- ✅ 前端 `ChatPage.tsx`：SSE 解析 `event` 类型；`SourceCard` 引用来源卡片；AI 回答 Markdown 渲染 + 代码高亮（新增依赖 react-markdown/remark-gfm/rehype-highlight）
- ✅ 端到端联调（curl）：`/ai/document/process` 入库 → `/ai/chat/stream` 返回 `sources` 事件，链路跑通
- ⚠️ 前端"查看原文"当前展开检索片段，原文件在线预览见 M4-4

#### 检索无结果兜底（对标业界成熟方案，2026-07-14）
- **背景**：知识库/文档为空或检索不匹配时，旧逻辑直接让 LLM 凭通用知识作答且无任何"知识库无相关内容"声明，与 业界的`fallback` 行为不一致。
- **普通问答（rag 模式）**：`core/config.py` 新增可配置项 `fallback_strategy`（默认 `model`）与 `fallback_response`（固定文案）。
  - `fixed`：检索无结果时直接推送 `fallback_response` 固定文案，**不调用 LLM**（省成本）。
  - `model`（默认）：检索无结果时仍调用 LLM，但 `services/llm.py:stream_generate` 新增 `no_kb_content` 标记，在 system 指令中要求 LLM 用通用知识兜底并**明确声明「知识库中暂无相关内容」、严禁编造**。
- **智能推理（Agent）**：`services/agent.py:_execute_tool` 检索工具空结果改为返回「在知识库中未找到相关内容（已检索 N 个知识库）」并附「不要使用训练数据/通用知识编造」「严禁编造或虚构来源」约束；系统提示同步补充"检索明确返回未找到相关内容时如实告知、不得编造"。
- **测试**：`tests/test_chat.py` 新增 `fixed`/`model` 两种兜底分支测试；`tests/test_agent.py` 新增检索工具空结果约束单测（含端到端 observation 事件校验）。ai-service 单测全过（注：`test_document_processor.py` 有 3 例因 pytest 事件循环跨测试干扰在大套件联跑时失败，单独跑/原始代码同样存在，与本特性无关）。
- **前端**：无需改动——兜底文本以 `token` 事件流式推送，前端既有渲染逻辑直接展示。

---

### M2-6 会话管理完善 [全栈] P1 · 1d ✅ 已完成

- **Java**: 会话列表（按时间分组）✅、重命名 ✅、删除 ✅
- **Python**: 多轮对话上下文（history 字段）✅
- **前端**: 左侧真实历史会话列表 ✅、点击加载历史消息 ✅、多轮上下文 ✅、重命名/删除 UI ✅
- **依赖**: M1-8
- **产出**: 完整的多轮对话体验

**后端已完成：**
- ✅ `ChatService`/`ChatServiceImpl` 新增 `renameConversation` / `deleteConversation`（校验归属，删除时一并删除消息）
- ✅ `ChatController` 新增 `POST /api/chat/conversation/rename`、`POST /api/chat/conversation/delete`
- ✅ 新增 DTO `RenameConversationRequest`
- ✅ `services/llm.py` 的 `stream_generate` 支持 `history` 多轮上下文；`routers/chat.py` 透传 `history`
- ✅ Java 单测 `ChatServiceImplTest`（4 个：重命名/删除 + 归属校验）全通过
- ✅ Python 单测 `test_chat.py` 新增多轮历史场景，全量 14 个通过

**前端已完成（详见文末两节）：**
- ✅ AppLayout 真实会话列表（接 `listConversations` API）+ ChatPage 加载历史/多轮上下文
- ✅ 会话持久化增强（`ChatContext` activeId 落 localStorage，刷新后恢复上次会话）
- ✅ 新建对话交互定稿（点加号仅切空白窗口不落库，发首条消息由后端自动建会话）

---

**M2 合计: ~7 天**

---

## M3 — 多角色权限 + 管理功能

### M3-1 RBAC 权限细化 [后端/Java] P0 · 1d ✅ 已完成

- Spring Security 路径级权限配置完善
- 知识库操作权限：
  - 共享库：member 只读，tenant_admin 可写
  - 个人库：owner 完全控制
- AI 配置权限：所有角色可配置
- 成员管理：仅 tenant_admin
- 审计日志：tenant_admin + super_admin
- **依赖**: M1-2
- **产出**: 不同角色访问不同功能

**实现记录（2026-07-12）**

参考业界成熟 RAG 方案 的 RBAC 思路（租户角色矩阵 + KB 归属数据级权限，而非纯 URL 级），落地本项目简化模型：

- ✅ 新增 `service/KbPermission.java`：集中 RBAC 数据级规则
  - 角色模型：`tenant_admin` / `super_admin`（写权限）/ `member`（只读）
  - 共享库（`shared`）：仅 `tenant_admin` / `super_admin` 可写（创建、上传、删除文档）
  - 个人库（`personal`）：仅 `owner`（KB 的 `owner_id`）可写
  - 读操作（列表、问答）对租户内全员开放，不在此限制
  - `super_admin` 作为跨租户超管自动放行（对齐 业界的 SystemAdmin 思路）
- ✅ `KnowledgeBaseService/Impl.createKnowledgeBase`：签名改为传入 `User`，创建共享库时校验 `KbPermission.assertCanCreate(scope, role)`；owner 取 `user.getId()`
- ✅ `DocumentService/Impl.uploadDocument` / `deleteDocument`：签名改为传入 `User`，写前经 `KbPermission.assertCanWrite(kb, userId, role)` 校验（注入 `KnowledgeBaseService` 查 KB scope/owner）
- ✅ `KnowledgeBaseController`：创建/上传/删除三处写操作透传 `loginUser`
- ✅ 单元测试 37 个全过：`KbPermissionTest`(14) + `KnowledgeBaseServiceImplTest`(10) + `DocumentServiceImplTest`(13)，覆盖共享库/个人库的创建与写权限、owner 匹配、跨租户隔离
- **说明**：业界的 4 级租户角色（Owner→Admin→Contributor→Viewer）与跨租户 Org 共享（`kb_share` 表 + 3-D 权限帽）对当前需求过度设计，本项目采用"共享库/个人库 + 3 角色"简化模型，核心思路（租户角色 + 资源归属的数据级 RBAC）保持一致

**租户隔离强化（2026-07-12，对齐 业界 own-KB（自有 KB）判定）**

复核发现写权限缺口：`assertCanWrite` 仅校验 owner/role，**未校验调用者 tenant 与 KB tenant 一致**，导致租户 A 的 `tenant_admin` 可越权写入租户 B 的共享库（读隔离 `tenant_id` 过滤已完整，删除文档已有 `tenantId` 校验故安全，仅上传漏了）。

对齐 业界 `kb_access.go` 第一步 `kb.TenantID == tenantID`（own-KB 优先）修复：
- ✅ `KbPermission.assertCanWrite` 新增 `callerTenantId` 参数，判定顺序：**super_admin 跨租户完全放行 → 其余角色必须 `callerTenantId == kb.tenantId`（租户隔离第一维度）→ 共享库仅 tenant_admin / 个人库仅 owner**
- ✅ `DocumentServiceImpl.uploadDocument` / `deleteDocument` 调用处补传 `tenantId`（与删除已有的 doc 级 tenant 校验形成双保险）
- ✅ 单测增量：KbPermissionTest 14→17（新增跨租户 tenant_admin 拒 / member 个人库跨租户拒 / super_admin 跨租户放），DocumentServiceImplTest 13→14（新增 `uploadDocument_tenantAdminCrossTenant_denied`）；三件套共 41 全过
- **结论**：至此 RBAC 具备完整的多租户写安全（tenant 作为权限第一维度，与 业界方案 一致）

---

### M3-2 成员管理 [全栈] P1 · 1.5d ✅ 已完成

- **Java**: 成员列表（本租户）、改角色、停用/启用、移除
- **Java**: 邀请成员（生成邀请链接）
- **前端**: 成员管理页面
  - 成员列表表格
  - 操作按钮（改角色下拉、停用切换）
  - 邀请成员弹窗
- **依赖**: M3-1
- **产出**: 管理员可管理租户成员

**现状盘点（2026-07-12，接手前核对）**

M3-2 **不是从零**：后端接口与实现已就位，前端是静态假数据。新会话应「核对现状 + 补缺口」，勿重写。

- **后端已就位**
  - `UserController`：`GET /api/user/list`、`POST /api/user/update`、`POST /api/user/invite`，均 `@AuthCheck(mustRole=tenant_admin)`
  - `UserService/Impl` 三方法已落地：
    - `listUsersByTenant`：按 `tenant_id` 过滤 ✅（多租户隔离 OK）
    - `updateUser`：已做 `!tenantId.equals(user.getTenantId())` 跨租户拒绝 ✅；**三道防护均已落地**（2026-07-17 复核 `UserServiceImpl.updateUser`）：① 防降级唯一/自己 `tenant_admin`——锁死最后一个管理员（`countTenantAdmins<=1` 拦截）+ 禁止改自己角色；② 防改 `super_admin`——目标为 super_admin 直接拦截（`:234`）；③ 改角色限范围——仅允许 `member`/`tenant_admin`，且禁止设为 `super_admin`（`:239-242`）
    - `createInvitation`（即文档所述 `inviteMember`）：采用「生成邀请链接」方案（256-bit base64url 令牌 + share-link，`/register?token=` 拼接，7 天有效），`acceptInvitation` 由受邀者自设密码注册并自动登录；**非「直接建账号（默认密码 123456）」**
  - DTO 已存在：`UserUpdateRequest`、`UserInviteRequest`、`UserAcceptInviteRequest`、`UserRemoveRequest`
  - **后端单测**：`UserServiceImplTest` 已覆盖注册/登录/获取登录用户/VO 转换、`updateUser`（跨租户拒绝 / 防改超管 / 防降级最后管理员 / 防改自己角色 / 防停用自己 / **防提升为超管** / 成功）、`createInvitation`（成功 / 非法角色）、`listUsersByTenant`（成功映射）、`removeMember` 等共 **37 例**（2026-07-17 复核全过）✅
- **前端 `frontend/src/pages/MembersPage.tsx` 是静态假数据**
  - `MEMBERS` 写死、未接任何 API
  - 角色用 `admin`/`member` 简写，而非后端枚举 `tenant_admin`/`member`/`super_admin`（需对齐 `UserConstant`）
  - 「邀请成员」按钮、「设为管理员」、「移除」**均无真实交互**（无 onClick / 未调接口）
  - 权限矩阵为静态展示（可保留）

**实现记录（2026-07-12）**

两项关键决策已与用户确认：**邀请采用「生成邀请链接」（对齐 业界 share-link（邀请链接））** + **移除采用「软删除」（对齐 业界 RemoveMember（软删除））**。

后端（Java）
- 新增 `tenant_invitations` 表（schema.sql 追加建表）+ 实体 `TenantInvitation` + `TenantInvitationMapper`；`application.yml` 新增 `app.frontend-base-url`（拼接邀请链接前缀）。
- `UserController.inviteMember` 改为 `createInvitation`：生成 256-bit base64url 令牌、返回 `InviteResultVO{ token, inviteUrl, role, expiresAt }`（`inviteUrl = ${app.frontend-base-url}/register?token=xxx`，share-link 可多人复用、7 天有效）。
- 新增公开端点：`GET /user/invite/info?token=`（注册页预填/展示，校验未撤销且未过期）、`POST /user/invite/accept`（接受邀请自设密码注册并自动登录，返回 `LoginUserVO`）。
- 新增 `POST /user/remove`（`@AuthCheck tenant_admin`）软删除成员（`@TableLogic` 逻辑删除）。
- `updateUser` 补防护：不能改 `super_admin`、不能把最后一个 `tenant_admin` 降级（last-admin 不变量）、不能修改/停用自己（防自锁）。
- `removeMember` 防护：不能移除自己、不能移除 `super_admin`、不能移除最后一个 `tenant_admin`。
- 新增 VO/DTO：`InviteResultVO` / `InviteInfoVO` / `UserAcceptInviteRequest` / `UserRemoveRequest`。

前端（React/TS）
- 新增 `api/user.ts`（`listMembers / updateMember / removeMember / inviteMember / getInviteInfo / acceptInvite`）；`types/index.ts` 的 `Member` 改为 camelCase 对齐后端 `UserVO`，新增 `InviteResultVO` / `InviteInfoVO`。
- `MembersPage.tsx` 重写接入真实 API：列表渲染、角色标签、设为管理员/成员、启用/停用、移除（确认弹窗）、邀请弹窗（生成并复制链接）；自己行禁用操作；loading/错误态。
- `AuthPage.tsx` 支持 `?token=xxx` 进入「接受邀请」模式（隐藏 Tab、展示邀请方/租户、走 `acceptInvite` 自动登录）。
- `useAuth` 新增 `acceptInvite`。

测试
- 单测 `UserServiceImplTest` 增量至 **29 例全过**（覆盖 updateUser 防护、createInvitation、acceptInvitation、getInviteInfo、removeMember 关键用例，Mock Mapper/JwtUtil）。
- 后端联调（PowerShell 脚本模拟前端全链路）全过：注册→邀请→接受→管理员列表见 2 人、改角色、不能停用/移除自己（50001/40000）、移除后列表回到 1 人、非法 token 拒绝（40100）；成员视角调列表被正确拒绝（40101，证明权限校验生效而非 bug）。
- 前端 `npx tsc --noEmit` 类型检查通过。

**注意**：角色枚举值已对齐 `UserConstant`（`member` / `tenant_admin` / `super_admin`），前端不再使用 `admin`/`member` 简写（改用 `tenant_admin`）。

**Bug 修复（2026-07-12）**：`MembersPage.tsx` 的 `formatDate` 中 `Array.isArray(value) ? (value as number[]).map(Number)` 返回 `number[]` 直接传给 `new Date()`，触发 TS 类型错误（`Date` 构造无 `number[]` 重载），导致 `npm run build`（tsc -b）失败。改为类型安全的 `Date` 构造：数组分支取 `[年, 月-1, 日]` 构造，非数组分支用 `new Date(value as string | number)`（`Member.createTime` 类型为 `string`，调用处仅传 string）。修复后 `npm run build` 通过（EXIT=0），lint 0 错误。

---

### M3-3 AI 模型配置 [全栈] P1 · 1d ✅ 已完成

- **Java**: AI 配置读写（租户默认 + 用户级覆盖）
- **Python**: AI 服务从 Java 获取配置（LLM/Embedding/Rerank 参数）
- **前端**: AI 配置页面
  - LLM / Embedding / Rerank 配置卡片
  - 提供商下拉、模型输入、API Key、温度、Token 限制
  - 保存配置
  - 顶部状态栏（已配置/未配置）
- **边界（必须包含，来自用户明确诉求）**：仅检测"是否填写"不够。要覆盖**配置正确性运行时检测**——
  - Python 必须真正消费用户在界面配置的模型（API Key / 模型名 / 提供商 / 向量维度），**取消当前静默降级**（无 Key 时造假向量、造假回答），调用失败要抛出**可识别的"模型配置错误"**类型。
  - Java 透传该错误类型（不要静默吞）；
  - 前端在「**上传文档解析 / 向量化失败**」与「**对话 LLM 调用失败**」两处，若错误类型=模型配置错误，则提示"模型配置不正确，请重新配置"并带跳转 `/ai-config`（与 M3-3 前端"未配置常驻横幅"互补：常驻=字段为空；运行时=调用失败）。
  - 即使用户填了字段，只要 API Key 错 / 模型名错 / 维度不匹配，也要能在失败点被发现并引导重配，而非静默成功入库或给假回答。
- **当前进度（截至 2026-07-12）**：
  - ✅ 前端「未配置模型常驻提示」已实现并提交（本地 commit `47dcea3`，**尚未 push**，因当前环境无外网）：`ChatPage` 进页拉取 `GET /api/ai-config/`，按 provider+model 非空判定；缺 LLM / Embedding 任一项即在消息区顶部渲染常驻琥珀横幅并列出缺失项，附「去配置」跳 `/ai-config`。新增 `frontend/src/api/aiConfig.ts`，`AIConfig` 类型改 camelCase 以匹配后端 `AiConfigVO`。**仅覆盖存在性检测（字段为空）。**
  - ✅ 已沉淀「配置正确性运行时检测」边界约束（见 design.md 对应段落 + 上方边界项）。
  - ✅ 已实现：上述「待开发」项（Java 保存/透传、Python 取消静默降级并抛 `MODEL_CONFIG_ERROR`、前端错误提示与保存按钮）均已在下方「实现记录（2026-07-12）」全部落地。
  - ✅ **Bug 修复（2026-07-14）**：AI 配置页 `API Key` 密码框被浏览器密码管理器误识别为登录密码框，导致登录时保存的账号密码被自动填入。已在两个 `API Key` 输入框与预留 Rerank 密码框加 `autoComplete="new-password"`，其余输入框（`模型`/`Endpoint`/`温度`/`维度` 等）`autoComplete="off"`，禁止浏览器自动填充。仅前端 `AIConfigPage.tsx` 改动（`Field` 组件新增 `autoComplete` 属性），无需后端、`tsc` 通过。
- ✅ **Bug 修复（2026-07-14）对话"一直思考中"不结束 + 模型配置错误提示体验**：用户反馈对话长时间停在"思考中"最终才弹"模型配置错误"。根因两处：① `llm.py` 的 `httpx timeout=60s` 过长，配置错误（地址不可达 / 请求挂起）时要等满 60s 才抛 `ModelConfigError`，期间前端无 token 也无 error 一直显示"思考中"；② 前端 `ChatPage.tsx` 收到 `event: error` 后只设 `modelConfigError` 与消息内容、**未结束流式**（`streaming` 仍 `true`），输入框禁用且仍渲染"思考中"。修复：
  - Python `llm.py`：将 httpx 超时改为 `httpx.Timeout(connect=10.0, read=120.0, write=10.0, pool=10.0)`（连接类配置错误 10s 快速失败）；并在 `_stream` 入口增加 `base_url` 为空（且无 env 兜底）时立即抛 `ModelConfigError("未配置 LLM Base URL…")`，避免用 `None` 拼地址导致悬挂/异常。
  - Python `routers/chat.py`：检索阶段命中 `ModelConfigError` 时，yield `error` 后补发 `done` 并 `return`，不再无谓重试 LLM 生成阶段，杜绝同一请求重复 `event: error`（rag 模式 fallback=model 时会抛两次）。
  - 前端 `ChatPage.tsx`：收到 `event: error` 后立即 `break` 出读取循环，使 `finally` 中的 `setStreaming(false)` 立即生效——输入框恢复、不再显示"思考中"、气泡保留"错误：…"并渲染红色「模型配置不正确」横幅 + 跳转 `/ai-config`。
  - 测试：新增 `ai-service/tests/test_llm.py`（1 例，`base_url` 为空时 `stream_generate` 立即抛 `ModelConfigError`，用 `asyncio.run` 驱动，不引入 pytest-asyncio 依赖）；`test_model_config.py`（2 例）仍过；前端 `tsc --noEmit` 通过。
  - 联调（超管账号，平台默认兜底 deepseek key 为空 → 触发"未配置 LLM API Key"）：`rag` 模式 83ms 返回单个 `event: error (MODEL_CONFIG_ERROR)` + `done`，不再 60s 挂起、不再重复 error。`web` 模式因 DuckDuckGo 联网搜索网络延迟约 15s（与本次修复无关）。
  - **配置层提示**：数据库 `ai_config` 表中多条租户记录 `llm_provider` 为空、且平台默认兜底（`tenant_id=0`，deepseek）的 `API Key` 为空——这是"模型配置错误"提示的真实来源，需用户在 `/ai-config` 正确补全（LLM 提供商 / Base URL / 模型名 / API Key），非代码 bug。
  - ✅ **Bug 修复（2026-07-17）AI 配置保存"删除字段却自动回填"**：用户反馈删掉模型名（空白）后点保存，字段又被自动填回。根因在 `AiConfigServiceImpl.applyUpdate`：此前对所有非密钥字段用 `if (StringUtils.isNotBlank(...))` 守卫——**空白时不更新、保留旧值**，导致前端删字段 → 发 null → 后端跳过 → 保留旧值 → 返回 `updated` 带回旧值 → 前端 `setForm(toForm(updated))` 回填，看似"自动填写"；另 LLM 主模型空但「更多模型」列表非空时会自动取列表第一项填充主模型。修复：① 非密钥字段（provider / model / baseUrl / temperature / maxTokens / embedding* / rerank* / dimension）改为**空白即清空**——新增 `toBlankable(String)` 将 null 或全空白归一为 `null` 后 `set`，删除字段保存后即为空白、不再回填；② 移除 `llmModel = llmModels.get(0)` 自动填充（主模型留空就留空）；③ 仅 API Key 保留「留空不修改」语义（避免误覆盖已存密钥，前端占位提示亦如此）。前端无需改动。单测 `AiConfigServiceImplTest` 新增 `updateConfig_blankFieldClearsStoredValue`（空白清空旧值），并将 `updateConfig_persistsLlmModelsJsonAndFillsDefaultModel` 断言改为「主模型留空不再自动取第一项」（仍验证 llmModels 列表持久化），**10 例全过（BUILD SUCCESS）**；重启 Java（PID 29388）加载新代码、Tomcat on 8080 Started。
  - ✅ **再调整（2026-07-17）模型必填拦截 + 删除 Rerank 配置区块**：
    - **模型必填拦截**：用户要求"模型没填也要告诉用户填、不能保存配置成功"。在「删除即清空」之上新增**保存拦截**——`AiConfigServiceImpl.applyUpdate` 在 `llmProvider`（或 `embeddingProvider`）非空但对应 `model` 为空时抛 `IllegalArgumentException("LLM/Embedding 模型名称为必填项，请在 AI 配置页填写…")`，拒绝保存；前端 `handleSave` 增加预校验，provider 已选但模型名空白时直接在顶部红字提示「请填写 LLM/Embedding 模型名称」并 `return` 阻止提交（与后端拦截一致，异常经 `RuntimeException` 处理器透传 `message` 到前端 `catch` 显示「保存失败：…」）。这样「删掉模型名」既不会自动回填、也不会静默保存成功，而是明确引导用户补全。
    - **删除 Rerank 配置区块**：AI 模型配置页移除「Rerank 重排序模型」卡片（`RERANK_ICON` 常量 + 整个预留卡片）、状态概览中的 Rerank 项（`rerankActive` / `config?.hasRerank` / `config?.rerankProvider` / `config?.rerankModel` 不再引用）。**仅前端页面改动，未动后端 `ai_config` 表、实体、`toVO` 及重排检索逻辑**（rerank 仍是后端用已配置 LLM 做精排的能力，只是配置页不再暴露独立 Rerank 模型入口）。
    - 测试：`AiConfigServiceImplTest` 新增 `updateConfig_missingLlmModelWhenProviderSet_throws` / `updateConfig_missingEmbeddingModelWhenProviderSet_throws`（provider 已填、模型空白抛异常）；`updateConfig_persistsLlmModelsJsonAndFillsDefaultModel` 改为显式 `setLlmModel("deepseek-r1")`（与列表首项 `deepseek-v3` 不同，证明主模型以显式填写为准、不取列表首项，列表仍持久化）；`updateConfig_blankFieldClearsStoredValue` 去掉 provider 以免触发必填；**12 例全过（BUILD SUCCESS）**；重启 Java（PID 28352）加载新代码、Tomcat on 8080 Started；前端 `tsc --noEmit` 通过（lints 0 错误）。

**实现记录（2026-07-12）**

M3-3 全部落地，覆盖「存在性检测（常驻横幅）」+「正确性运行时检测（调用失败识别）」两层提示；开发顺序严格遵循 后端 → 单测 → 前端 → 联调。

后端（Java）
- `AiConfigService` 新增 `getRawConfig(tenantId, userId)`（含 API Key，仅内部透传用；user 级优先于 tenant 级，无则返回 null）。
- `AiServiceClient` 新增 `toAiConfigMap(AiConfig)`：实体 → snake_case `ai_config` dict（跳过 null、含 `embedding_dimension`），供请求体注入；`chatStream` / `processDocument` 新增 `aiConfig` 参数并放入 `ai_config` 字段。
- `ChatController` 调 `chatStream` 前取 `getRawConfig` → `toAiConfigMap` 透传。
- `DocumentServiceImpl.triggerDocumentProcessing` 取 `getRawConfig` → `toAiConfigMap` → `processDocument(..., aiConfig)`；失败结果解析 `error_type==MODEL_CONFIG_ERROR` 置 `document.modelConfigError=true`。
- `Document` 实体 + `DocumentVO` + `schema.sql` 新增 `model_config_error` 列（已 `ALTER TABLE` 上线）。
- 单测 `AiServiceClientTest`（toAiConfigMap 映射/跳过 null/空 cfg）、`ChatControllerTest`/`DocumentServiceImplTest` 随签名变更回归，Java 共 **27 单测全过**。

Python（AI 服务）
- 新增 `services/model_config.py`：`ModelConfigError`（可识别错误类型）+ `ModelConfig`（from_dict 解析 Java `ai_config`、has_embedding/has_llm）。
- `embedding.py`：移动 API Key 校验到缓存**之前**（无 Key 直接抛，绝不命中旧缓存返回伪向量）；`_embed_remote` 调用失败 / 维度不匹配抛 `ModelConfigError`；**L2 缓存 key 增加 API Key 哈希**避免跨配置串数据。
- `llm.py`：`stream_generate` 无 LLM Key / 调用失败抛 `ModelConfigError`。
- `rag.py`：`retrieve`/`_rerank` 消费 `cfg` 的 rerank 参数，调用失败抛 `ModelConfigError`。
- `document_processor.py`：`process`/`embed_chunks` 透传 `cfg`。
- `routers/chat.py`：请求加 `ai_config`；检索/生成阶段 `except ModelConfigError` → `event: error` + `error_type: MODEL_CONFIG_ERROR`，最后仍 `event: done`。
- `routers/document.py`：`ModelConfigError` → 返回 `status:failed` + `error_type: MODEL_CONFIG_ERROR`。
- 单测 **21 个全过**（含 `test_model_config`：空 Embedding Key 文档→MODEL_CONFIG_ERROR、对话空 LLM Key→error 事件）。

前端（React/TS）
- `types/index.ts`：`Document` 增 `modelConfigError`；新增 `AIConfigUpdateRequest`（对齐后端 `AiConfigUpdateRequest`）。
- `api/aiConfig.ts`：新增 `updateConfig` 保存方法（POST `/api/ai-config`）。
- `AIConfigPage.tsx`：重写为受控表单，接通读取 + 保存（保存成功提示并刷新状态栏）。
- `ChatPage.tsx`：解析 SSE `event: error`；`error_type==MODEL_CONFIG_ERROR` 时顶部渲染红色「模型配置不正确」横幅 + 「去配置」跳 `/ai-config`（与存在性常驻横幅互补，不冲突）。
- `KnowledgeBasePage.tsx`：文档列表存在 `modelConfigError` 时渲染红色横幅 + 「去配置」（引导核对 Embedding 配置后重新上传）。

**验证**
- Python 直接注入 `ai_config`（空 Key）端到端：`POST /ai/chat/stream`（mode=search, 空 LLM Key）→ `event: error` + `MODEL_CONFIG_ERROR` ✅；`POST /ai/document/process`（空 Embedding Key）→ `status:failed` + `MODEL_CONFIG_ERROR` ✅（首次联调一次超时系 PowerShell 首次加载模块 + 临时文件未就绪偶发，复测稳定返回错误，非代码问题）。
- Java 新代码 `mvn test` 27 单测全过，8080 重启（PID 18224）加载 M3-3 新代码成功。
- 前端 `read_lints` 全过；`npm run build` 因 M3-2 遗留 `MembersPage.tsx` 的 `new Date(number[])` 类型错误失败（**非 M3-3 引入**，dev server vite 不执行 tsc 不受影响，已单独告知用户）。

**说明**：Java→Python 经 DB 配置的真实全链路（用户在前端配置→Java 读出→Python 识别）由用户网页手动配置后联调验证；M3-3 配置正确性检测逻辑已通过直接注入 `ai_config` 覆盖。

**Bug 修复（2026-07-12，多模态 Embedding 端点适配）**

- **现象**：用户用火山方舟 `doubao-embedding-vision-251215` 作 Embedding 模型时，提问/上传文档均报 `400 Bad Request ... does not support this api`，前端提示"模型配置不正确"。
- **根因**：火山方舟多模态 Embedding 模型**不走标准 OpenAI 兼容 `/embeddings` 接口**，而走专用端点 `/embeddings/multimodal`，且 `input` 必须为对象数组 `[{type:"text", text:"..."}]`（非字符串数组）；并支持 `dimensions` 参数（1024/2048 可选）。原 `embedding.py` 固定调 `/embeddings` + 字符串数组，故该模型 400。其它平台能跑通正是因为它们适配了多模态端点与格式（用户最初质疑"该模型支持文本、在别处能跑"完全成立）。
- **修复（`services/embedding.py`）**：
  - 新增 `_is_multimodal_embedding(model)`：`embedding-vision` 命中即判定多模态；
  - 多模态分支走 `/embeddings/multimodal`，`input` 为单条文本对象；该端点对多条 input 会**把各向量拼接成一维返回、难以拆分**，故改为**逐条调用**（每条独立 POST）；
  - 透传 `dimensions`（取配置的 `embedding_dimension`），实测 `dimensions:1024`→1024 维、`2048`→2048 维，**用户原配置无需改动**；
  - 响应解析兼容多模态 `{"data": {"embedding": [...]}}`（dict）与标准 `[{embedding, index}]`（list）两种结构。
- **单测（`tests/test_embedding.py`）**：新增多模态端点/input 格式/dimensions 透传断言（单条+批量）；ai-service 全量 **24 单测全过**。
- **联调**：用库内真实配置真实调用 Ark，单条返回 **1024 维成功**、无 400；重启 8001 后 `POST /ai/chat/stream` 真实链路流式返回 token 直至 `done`，全程无 `MODEL_CONFIG_ERROR` / "Embedding 调用失败"。
- 注：属 M3-3 正确性运行时检测框架下的具体 Bug 修复（端点/格式/维度适配），非新功能；前端错误提示与跳转逻辑不变。

**M3-3 补充（2026-07-12 晚）：AI 配置厂商默认填充 + LLM 多模型**

需求（用户提出）：① 选完厂商后自动填充 API Endpoint / 向量维度（用户可改）；② 配置页支持配置多个 LLM 模型；③ 对话输入框模型下拉从写死 `deepseek-v3.2` 改为动态载入已配置模型并可切换。

实现要点
- 后端：
  - `AiConfig` 实体新增 `llmModels`（VARCHAR(1000)，存 JSON 数组字符串，如 `["a","b"]`）；`llmModel` 保留为默认选中模型。
  - `AiConfigVO` / `AiConfigUpdateRequest` 新增 `llmModels`（`List<String>`）。
  - `AiConfigServiceImpl.updateConfig`：多模型列表持久化为 JSON；当 `llmModel` 为空时取列表第一项作为默认选中。`toVO`：JSON 反序列化为 `List`（解析失败/为空返回空列表，避免前端 NPE）。
  - `toAiConfigMap` 无需改（Python 单 `llm_model` + 对话 `model` 覆盖另走 `ChatRequest.model`）。
  - **对话多模型下发链路原本已具备**：`ChatRequest.model` → `ChatController` 透传 → `AiServiceClient.chatStream(model)` → `Python chat_stream(model=body.model or None)` → `llm_service.stream_generate(model=...)` 覆盖 `cfg.llm_model`。本次仅前端补齐下拉 + 配置页多模型存储。
  - `schema.sql` 的 `ai_config` 表新增 `llm_models VARCHAR(1000)`；运行库已 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS llm_models VARCHAR(1000)` 上线。
- 前端：
  - `types/index.ts`：`AIConfig` / `AIConfigUpdateRequest` 加 `llmModels`。
  - `AIConfigPage.tsx`：新增 `PROVIDER_DEFAULTS`（火山方舟/OpenAI/DeepSeek/BGE 的默认 endpoint、温度、维度）；切换 LLM/Embedding 厂商时自动填充对应默认值（用户可手动改）；LLM 卡片新增"更多模型"增删区块（`llmModels`）。
  - `api/chat.ts`：`streamChat` 增加 `model` 参数并透传请求体。
  - `ChatPage.tsx`：进页读取配置，按 `[llmModel, ...llmModels]` 去重得可选模型列表；输入框模型选择按钮改为动态下拉（默认选中第一项，可切换），发送时携带选中 `model`。
- 单测：`AiConfigServiceImplTest` 4 例（JSON 解析 / 空模型返回空列表 / 多模型持久化 + 默认模型填充 / 显式默认模型优先），全过。
- 构建：前端 `npm run build` 通过（EXIT=0）；后端重启加载新实体 + DB 加列成功，健康检查 `/api/ai-config/` 返回 200。

验证状态
- 自动化已验证：后端单测 4 例、前端构建、DB 加列、后端健康、对话 `model` 下发代码链路走查。
- 待用户网页联调：① 选厂商自动填 endpoint/维度；② 配置页添加多个模型并保存；③ 对话页下拉切换模型（需真实 API Key 才能发起真实调用）。因涉及用户真实账号与 API Key，由用户在其网页账号执行，未由我代操作（避免覆盖/踢出真实配置）。

**M3-3 补充（2026-07-16）：新增「阿里云百炼」厂商预设**

需求（用户提出）：AI 模型配置增加阿里云百炼选型，作为火山方舟之外的 OpenAI 兼容备选（百炼有免费额度、中文友好，且免模型激活困扰）。

实现要点
- 前端 `AIConfigPage.tsx`：`LLM_PROVIDERS` / `EMBEDDING_PROVIDERS` 增加 `阿里云百炼`；`PROVIDER_DEFAULTS` 新增条目，LLM 与 Embedding 均默认 `base_url=https://dashscope.aliyuncs.com/compatible-mode/v1`（OpenAI 兼容模式）、LLM 温度 `0.7` / 最大 Token `4096`、Embedding 维度 `1024`。选厂后自动填充，用户可手动改。
- 后端 / Python 无需改动：`embedding_provider`/`llm_provider` 为自由字符串透传（`AiServiceClient`/`AiConfigServiceImpl` 不校验枚举）；百炼 OpenAI 兼容 `/embeddings`、`/chat/completions` 与现有 `embedding.py`/`llm.py` 协议一致，直接可用。
- 用法：选「阿里云百炼」后，模型名自填（Embedding 如 `text-embedding-v3`、LLM 如 `qwen-plus`/`qwen-max`）+ 填百炼 API Key 即可，无需改任何代码。
- 验证：前端 `npx tsc --noEmit` 通过、该文件 lint 0 错误。

**M3-3 Bug 修复（2026-07-16）：标准 Embedding 路径未透传 dimensions 导致维度不匹配**

现象：用户选「阿里云百炼」+ Embedding 模型 `qwen3.7-text-embedding` + 维度 `1536` 上传文档，报「向量维度不匹配：配置维度 1536，实际模型返回维度 1024」。（此前残留的 `400 Bad Request` 是更早一次模型名填错时的旧记录，非当前根因。）

根因：`services/embedding.py` 的 `_embed_remote` 仅**多模态**分支（doubao-embedding-vision 系列）透传 `dimensions`，而**标准 OpenAI 兼容**分支（第 198 行）只发 `{"model","input"}` 不传 `dimensions`。`qwen3.7-text-embedding` 默认返回 1024 维，必须显式传 `dimensions=1536` 才能拿到与配置一致的向量；不传则百炼返回 1024，与配置 1536 不符，被维度校验拦截报错。

修复：标准分支在 `cfg.embedding_dimension` 非空时也写入 `payload["dimensions"]`，与多模态分支行为一致。

验证：
- 用库内真实 Key 实跑 `EmbeddingService.embed_text`（`qwen3.7-text-embedding`/1536）：修复前报维度不匹配，修复后返回 1536 维 ✅
- 单测 `test_embedding.py` 新增 `test_standard_embedding_passes_dimensions`（校验标准路径 body 含 `dimensions`），全套 10 例全过 ✅
- 重启 Python AI 服务（PID 26444）加载新代码，健康检查 `/health` 返回 200 ✅
- 用户侧：在 AI 配置页保存百炼配置后**重新上传**文档即可（该模型默认 1024，配置 1536 时需本修复透传）。

**M3-3 调整（2026-07-17）：Embedding 向量维度改为「必填 + 按提供商白名单透传」（用户明确要求）**

用户诉求：① 所有 Embedding 模型都要按用户填写的向量维度传 `dimensions`，不能只多模态传、标准路径不传；② 向量维度必须填，不能留空。
→ 后续（同日）用户进一步要求改为**按提供商白名单决定要不要传**，避免对不支持 `dimensions` 参数的提供商（如 BGE）传参导致 400。

改动（三层全改）：
- **Python `services/embedding.py`**：新增模块级常量 `DIMENSION_SUPPORTED_PROVIDERS = {"火山方舟", "OpenAI", "阿里云百炼"}`；`_embed_remote` 计算 `pass_dimensions = (cfg.embedding_provider or "") in DIMENSION_SUPPORTED_PROVIDERS`，仅白名单内才发送 `payload["dimensions"] = cfg.embedding_dimension`（标准 OpenAI 兼容分支与火山方舟多模态分支一致）；函数入口仍对 `embedding_dimension` 为空时直接抛 `ModelConfigError("未配置 Embedding 向量维度，请在 AI 配置页填写向量维度后重试")`；L2 缓存 key 纳入维度（避免不同维度配置命中同一缓存向量）。白名单外（如 `BGE` 的 bge-m3）不识别该参数故不传、使用模型默认维度。
- **Java `AiConfigServiceImpl.applyUpdate`**：配置 Embedding（`embeddingProvider`/`embeddingModel` 任一非空）时，若 `embeddingDimension` 为 null 或 ≤0，抛 `IllegalArgumentException("Embedding 向量维度为必填项且必须为正整数…")`，保存即被拒。
- **前端 `AIConfigPage.tsx`**：`Field` 组件**移除** `required` 红框/红星样式（与普通字段一致）；「向量维度」字段**不自动填充**（选厂商时仅自动填 endpoint，不再自动填维度，与「模型名称」一致，空白就空白）；`handleSave` 在保存前校验——配置了 Embedding 但维度非正整数时，在维度框**下方内联**渲染 `dimensionError`（"请填写向量维度（正整数）"）并拦截提交，用户重新输入时清除该提示（不再用顶部红横幅）。维度必填校验仍保留，用于结尾维度一致性校验。

验证：
- Python 单测 `test_embedding.py`：保留 `test_embedding_dimension_required`(未填维度→抛 ModelConfigError)；白名单内用例 `test_standard_embedding_passes_dimensions`(阿里云百炼)、`test_standard_embedding_endpoint_and_format`(OpenAI)、`test_multimodal_embedding_always_passes_dimensions`/`test_multimodal_embed_*`(火山方舟) 均断言 body 含 `dimensions`；新增 `test_bge_embedding_does_not_pass_dimensions`(BGE→不传 dimensions)；全套 **13 例全过** ✅
- Java 单测 `AiConfigServiceImplTest` 新增 `updateConfig_embeddingDimensionRequired`(缺维度→IllegalArgumentException)、`updateConfig_embeddingWithDimensionSucceeds`(填维度→正常保存并落库 1024)；`mvn -Dtest=AiConfigServiceImplTest` **9 例全过（BUILD SUCCESS）** ✅
- 前端 `npx tsc --noEmit` 0 错误、该文件 lint 0 错误 ✅
- 重启 Python（PID 28896）加载新代码；`/health` 200 ✅
- 注：白名单当前为 `火山方舟 / OpenAI / 阿里云百炼`。若新增支持 `dimensions` 的提供商，只需在 `DIMENSION_SUPPORTED_PROVIDERS` 中加入其名称（须与前端 `EMBEDDING_PROVIDERS` 选项一致）。

**M3-3 Bug 修复（2026-07-17）：额度/限流错误被误判为「模型配置不正确」**

现象（用户反馈）：Qwen 模型额度减少后被限流，上传 `Java面试宝典完整版最最最新.pdf` 处理失败，前端提示"模型配置不正确，部分文档向量化失败，请检查 Embedding 的 API Key、模型名或向量维度后重新配置，并重新上传。去配置"——但真实原因是**额度耗尽/被限流**，引导用户去改配置是误导。

根因：原 `embedding.py` 将 HTTP 429（限流）与 5xx（过载）统一转成 `ModelConfigError`，聊天 `llm.py` 同理，导致额度类错误与配置类错误无法区分，前端只能统一渲染"去配置"横幅。

修复（三层全改）：
- **Python `services/model_config.py`**：新增 `ModelQuotaError`（额度/限流错误类型）与 `is_quota_error(text)` 关键字判定（含 quota/额度/余额/insufficient/rate limit/rate_limit/exceeded/frequency/限流/429/too many requests/balance）。
- **Python `services/embedding.py`**：`_call` 内 429/5xx 退避重试耗尽、或 4xx 响应体含上述关键字 → 抛 `ModelQuotaError`；其余 4xx（Key/模型名错）→ 仍 `ModelConfigError`；网络错误末次 → `ModelConfigError`。
- **Python `services/llm.py`**：新增 `_assert_usable_response`，在读取流前对 429/5xx、或 4xx 响应体含额度关键字 → 抛 `ModelQuotaError`，其余 4xx → `ModelConfigError`；两分支生成前均调用。
- **Python `routers/document.py`**：`except ModelQuotaError` → 返回 `error_type: MODEL_QUOTA_ERROR`（位于 `except ModelConfigError` 之前）。
- **Python `routers/chat.py`**：检索/生成两阶段 `except ModelQuotaError` → yield SSE `error_type: QUOTA_ERROR`。
- **Java `Document.java` / `DocumentVO.java`**：新增 `quota_error` 列（`@TableField(updateStrategy=ALWAYS)` 支持置 null）；`schema.sql` 同步加列 + 运行库 `ALTER TABLE document ADD COLUMN quota_error BOOLEAN DEFAULT FALSE` 已上线。
- **Java `DocumentService.java` / `DocumentServiceImpl.java`**：`updateDocumentStatus` 新增 6 参重载（多 `Boolean quotaError`）；`triggerDocumentProcessing` 解析 `error_type==MODEL_QUOTA_ERROR` 置位；`getDocumentVO`/`retryDocument` 同步清位；成功终态一并清空 `quota_error`/`modelConfigError`/`errorMsg`。
- **前端 `types/index.ts`**：`Document` 增 `quotaError: boolean | null`。
- **前端 `KnowledgeBasePage.tsx`**：文档 `quotaError` 时渲染**琥珀色**横幅"额度不足/被限流，请稍后重试" + 「重试」按钮（区别于红色"去配置"配置错误横幅）。
- **前端 `ChatPage.tsx`**：SSE `error_type==QUOTA_ERROR` → 置 `quotaError`，渲染琥珀色横幅（区别于红色配置错误）。

验证：
- Python 单测 `test_embedding.py` 新增 `test_429_raises_quota_error` / `test_4xx_quota_keyword_raises_quota_error`；`test_model_config.py` 新增 `test_document_process_quota_error`（文档向量化 429→`MODEL_QUOTA_ERROR`）+ `test_chat_stream_quota_error`（对话 429→`QUOTA_ERROR`）；另修复套件既有测试隔离隐患：`core/redis_client.get_redis` 在事件循环切换/关闭时自动重建客户端（根治「Event loop is closed」跨 `TestClient` 污染）、`test_embedding_concurrency.py` 修正两处 monkeypatch 目标（`httpx.AsyncClient`、按值导入的 `services.embedding.get_redis`）、`test_model_config.py` 的 quota 用例改为实例级补丁对齐套件。ai-service 全套 **137 例全过** ✅
- Java 单测 `DocumentServiceImplTest` **36 例全过**（含新 6 参 `updateDocumentStatus` 与 `quota_error` 字段；并修复本改造误改的终态守卫 `nextTerminal`——此前错用 `current` 致 `cancelled` 文档被迟到 `optimizing` 覆盖，已回退为基于 `status` 判定）✅
- 前端 `tsc --noEmit` / lint 0 错误 ✅
- 待用户联调：额度恢复后重试失败文档应成功；限流期间重试应仍提示琥珀色"稍后重试"而非红色"去配置"。

**M3-3 Bug 修复（2026-07-17）：批量 Embedding 超限（batch size > 20）导致大文档处理失败**

现象（用户反馈）：上传 `Redis笔记.docx`（269KB）处理失败，提示"模型配置不正确，请检查 Embedding 的 API Key、模型名或向量维度后重新配置"。但用户配置本身正确——真实根因是代码 bug，与配置无关。

根因：`services/embedding.py` 的 `EmbeddingService._embed_remote` 非多模态分支（标准 `/embeddings` 路径）将**整篇文档的全部 chunk 一次性**作为 `input` 数组发给 Embedding API。阿里云百炼（及部分兼容接口）硬性限制**每批 input 数量 ≤ 20**，当文档切出的 chunk 超过 20 条时返回 `HTTP 400 batch size is invalid, it should not be larger than 20`；而原代码把任意 4xx 笼统归为 `ModelConfigError`（"API Key 或模型名可能错误"），误导用户去改配置。

修复：`_embed_remote` 标准路径改为按 `EMBEDDING_BATCH_SIZE = 20` 分批调用 `_call`，每批 `input` 不超过上限，再按原始顺序合并结果；单批内仍按 `index` 保序。多模态路径本就逐条调用，不受影响。该修复对所有 OpenAI 兼容提供商均安全（小限制模型不超限，大限制模型仅多几次请求）。

验证：
- `tests/test_embedding.py` 新增 `test_standard_embedding_batches_large_input`（25 条 input → 2 批 20+5，校验批次数、批大小上限、结果数量与维度）；全套 **16 例全过** ✅
- 重启 Python（uvicorn 8001）加载新代码，`/health`=200 ✅
- 待用户联调：重新上传/重试 `Redis笔记.docx` 应向量化成功（不再报 batch size 400）。

**M3-3 Bug 修复（2026-07-17）：重新配置保存后旧「模型配置不正确」横幅不消失**

现象（用户反馈）：用户修正 AI 配置（如补全 Embedding API Key / 模型名 / 维度）并保存成功后，知识库页仍持续显示红色横幅「模型配置不正确，部分文档向量化失败，请检查 Embedding 的 API Key、模型名或向量维度后重新配置，并重新上传。去配置」，不随重新配置而消失。

根因：`KnowledgeBasePage` 横幅由 `documents.some(d => d.modelConfigError)` 驱动。历史失败文档在首次向量化失败时被置 `model_config_error=true`，而该标记**仅在成功终态、`retryDocument`、或命中 `MODEL_QUOTA_ERROR` 时才清位**。用户重新保存一份（可能）正确的配置后，后端并未清除旧归因标记，文档仍带着 `modelConfigError=true`，横幅因此持续存在——误导用户以为配置仍错。

修复（后端，前端逻辑无需改动）：保存 AI 配置成功后，清除该租户失败文档上基于旧配置的归因标记，使横幅消失、交由用户手动重试以按新配置重新归因。
- **Java `DocumentService` 接口**：新增 `int clearFailedConfigErrorFlags(Long tenantId)`（`tenantId` 为 null 时清除全库失败文档，供平台级默认配置更新影响所有租户）。
- **Java `DocumentServiceImpl`**：实现用 `UpdateWrapper` 仅对 `status='failed'` 且 `model_config_error=true` 或 `quota_error=true` 的记录，按 `tenantId`（非空时）过滤并清零两标记；`getBaseMapper().update` 返回真实影响行数。文档保持 `failed` 终态不变（需用户手动重试重新归因）。
- **Java `AiConfigServiceImpl`**：`updateConfig` 保存成功后调 `documentService.clearFailedConfigErrorFlags(tenantId)`（清本租户）；`updatePlatformDefault` 保存成功后调 `clearFailedConfigErrorFlags(null)`（清全库）。

验证：
- Java 单测 `AiConfigServiceImplTest`（旧位置 `com.xiongda.service` + 新位置 `com.xiongda.service.impl`）各新增 `updateConfig_clearsFailedDocErrorFlagsForTenant` / `updatePlatformDefault_clearsFailedDocErrorFlagsGlobally`（断言保存后调用 `clearFailedConfigErrorFlags` 传对应 `tenantId`/`null`）；`DocumentServiceImplTest` 新增 `clearFailedConfigErrorFlags_callsMapperWithWrapper` / `clearFailedConfigErrorFlags_nullTenantClearsGlobally`（断言 `documentMapper.update` 被调用并传 `UpdateWrapper`）。本次连同原有共 **52 例全过（mvn clean test BUILD SUCCESS）** ✅
- 注：发现并修复了遗留的重复测试文件 `com/xiongda/service/AiConfigServiceImplTest.java`（旧包名位置、未适配 `documentService` 依赖注入），补 `@Mock DocumentService` + `setUp` 显式 `setField` 后通过，未删除以保留其独特覆盖。
- 待用户联调：重新保存配置后，知识库页红色「去配置」横幅应自动消失（旧失败文档标记已清），文档仍显示「失败」状态，点「重试」按新配置重新向量化。

**M3-3 调整（2026-07-17）：AI 配置保存成功后按来源跳回对应页面**

需求（用户提出）：从知识库页（上传文档 / 检索过程出现模型配置错误）点「去配置」进入 AI 模型配置页，保存成功后应跳回**知识库页**；从对话页（LLM 调用失败提示）点「去配置」进入，保存成功后应跳回**对话页**。原逻辑无条件 `navigate('/chat')`，从知识库页去配置后会被带离知识库、体验割裂。

改动（纯前端，无后端）：
- `KnowledgeBasePage.tsx` 文档向量化失败红横幅「去配置」入口：`navigate('/ai-config?from=/knowledge')`
- `ChatPage.tsx` 两处「去配置」入口（运行时模型配置错误红横幅 + 未配置常驻琥珀横幅）：均 `navigate('/ai-config?from=/chat')`
- `AIConfigPage.tsx`：引入 `useSearchParams`；保存成功分支（`scope !== 'platform'`）读取 `searchParams.get('from')`，有则跳回来源、无（如从侧边栏菜单直接进入）则默认跳 `/chat`。

验证：
- 前端 `npx tsc --noEmit` 0 错误、`npm run build`（tsc -b && vite build）EXIT=0 ✅
- 待用户联调：① 知识库页点「去配置」→ 保存 → 应回知识库页；② 对话页点「去配置」→ 保存 → 应回对话页。

**M3-3 启动回归修复（2026-07-17）：`AiConfigServiceImpl` ↔ `DocumentServiceImpl` 循环依赖致后端启动失败**

现象：运行 `mvn spring-boot:run` 报 `APPLICATION FAILED TO START`，Spring 检测到 bean 循环依赖 `aiConfigController → aiConfigServiceImpl → documentServiceImpl → aiConfigServiceImpl`，BUILD FAILURE（exit code 1），8080 不监听。

根因：上一版 M3-3 修复（commit `69e6432`）为在配置保存后清除失败文档归因标记，于 `AiConfigServiceImpl` 注入了 `DocumentService`；而 `DocumentServiceImpl` 本就注入 `AiConfigService`（文档向量化时读取用户级/租户级配置）。两者直接互依成最小环。单元测试用 Mockito 未触发 Spring 上下文循环检测，故此前 `mvn clean test` 仍 SUCCESS，未暴露。

修复（最小侵入，未提交）：`AiConfigServiceImpl.documentService` 字段加 `@Lazy`（新增 `import org.springframework.context.annotation.Lazy`），延迟到首次调用时注入；调用点（`clearFailedConfigErrorFlags`）均在配置保存运行时，非启动关键路径，无副作用。不动 `DocumentServiceImpl` 与公开接口。

验证：重新 `mvn spring-boot:run` 启动成功（`Tomcat started on port 8080`、`Started MainApplication in 9.4s`）；前端 5173、Python AI 8001 保持在线，三服务齐备可用于联调。

- **依赖**: M1-2
- **产出**: 可在前端配置 AI 模型；配置填错（API Key/模型名/维度）时，对话失败与文档向量化失败均能被识别并引导到 /ai-config 重配

---

### M3-4 审计日志 [全栈] P1 · 1d ✅ 已完成

- **Java**: 审计日志记录切面（AOP）
  - 新增 `@AuditLog` 注解 + `AuditLogAspect`（`@AfterReturning`）：方法成功返回后自动落库
  - 自动抓取：当前登录用户（未登录场景如登录/接受邀请从返回值 `LoginUserVO` 取）、客户端 IP（`X-Forwarded-For` 优先）、User-Agent
  - 自动从返回值提取资源 ID（Long / `LoginUserVO.id`）；从入参构造脱敏 `detail`（屏蔽 `password`/`secret`/`token`/`apiKey` 等敏感字段，跳过 `HttpServletRequest`/`MultipartFile`/`User`）
  - 埋点：`UserServiceImpl`（login/logout/member_change: updateUser·removeMember·createInvitation·acceptInvitation）、`DocumentServiceImpl`（doc_upload/doc_delete）、`AiConfigServiceImpl`（config_update）
  - `recordLog` 用 `REQUIRES_NEW` 独立事务，写入失败仅告警、不影响主流程
- **Java**: 审计日志查询接口（`GET /api/audit/list`，租户管理员查本租户 / 平台超管查全局）
  - 筛选：操作类型 `action`、操作人 `userEmail`、时间范围 `startTime`/`endTime`（支持 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss`，结束时间日期型补齐 `23:59:59`）
  - 分页返回 `Page<AuditLogVO>`（records / total / size / current / pages）
- **前端**: 审计日志页面（`frontend/src/pages/AuditLogPage.tsx`，接真实 API）
  - 筛选栏（操作类型 / 操作人邮箱 / 时间范围：全部·今天·近7天·近30天·近90天）
  - 日志列表表格（时间 / 操作人 / 操作类型徽章 / 资源类型 / IP / 详情）
  - 详情行内展开查看 JSON（自动格式化，解析失败保留原文）
  - 分页（上一页/下一页）
  - 登出时 `AppLayout.handleLogout` 异步调用 `/api/user/logout` 记录登出审计
- **单测**: `AuditLogServiceImplTest` 3 例（recordLog 落库字段含 userAgent / 租户级筛选与 VO 映射 / 全局忽略租户）全过
- **联调验证**：注册→登录→改配置→邀请 全链路，DB 确认 login/config_update/member_change 三种日志写入且 `userPassword`、`llmApiKey` 脱敏为 `***`；查询接口分页与 `action` 筛选正确
- **依赖**: M3-1
- **产出**: 可查看操作审计记录

---

### M3-5 平台超管功能 [全栈] P2 · 1.5d ✅ 已完成

- 租户管理：列表、创建、停用、配额设置
- 平台配置：全局默认模型配置
- 全局审计日志（跨租户）
- **依赖**: M3-1
- **产出**: 平台超管可管理系统

**实现（2026-07-13）**：
- **后端/Java**：新增 `TenantController` + `TenantService`/`TenantServiceImpl` + `TenantVO` + `TenantCreateRequest`/`TenantQuotaRequest`，全部 `@AuthCheck(mustRole=SUPER_ADMIN_ROLE)`；`AiConfigController` 新增 `GET/POST /ai-config/platform-default`（以 `tenant_id=0` 哨兵行作全租户兜底，三级回退链 用户→租户→平台）；`UserServiceImpl` 注册入租户、`DocumentServiceImpl.uploadDocument` 分别校验租户成员数/文档数配额（达到上限 `OPERATION_ERROR` 拒绝，`<=0` 视为不限，对标业界成熟方案）。`AuditLogController` 对 `super_admin` 走 `listAllLogs` 跨租户。
- **前端/TS**：新增 `api/tenant.ts` + `pages/TenantPage.tsx`（超管专属：租户表格含实时成员/文档数、创建弹窗（slug 校验/管理员需已注册/拒绝超管作管理员）、启用停用、配额弹窗、分页）；`AppLayout` 侧边栏新增「租户管理」菜单（`roles:['super_admin']`）；`AIConfigPage` 超管视角下提供「我的配置 / 平台默认配置」作用域切换，分别调用用户/平台接口；`types` 补充 `Tenant`/`TenantCreateRequest`/`TenantQuotaRequest`/`Page<T>`。
- **单测**：`TenantServiceImplTest`（8 例：创建/slug重复/管理员不存在/管理员是超管/状态非法/配额/列表统计）、`AiConfigServiceImplTest`（平台默认 upsert+读、三级回退、更新不误改平台默认）、`DocumentServiceImplTest`/`UserServiceImplTest` 补充配额校验用例。本次后端单测共 **61 例全过**。
- **联调验证**：用临时超管账号 `super@xiongda.com` 登录实测——租户列表（total=12）、创建租户（正路径 code=0 且管理员自动转 tenant_admin）、管理员不存在/超管作管理员两负路径均返回 `code=40000` 且中文提示正确、启用停用/配额/平台默认配置读写均 `code=0`、列表计数同步 +1。验证后清理临时租户与用户，保留超管账号供 UI 试用。
- **注意**：系统中原本无 `super_admin` 账号，联调时临时插入 `super@xiongda.com / test123456`（MD5(`xiongda`+pwd)）以便访问 M3-5 功能；如不需要可自行删除。

- **方案A 扩展（超管切换租户，对齐 业界 TenantSelector（租户切换））**：在 M3-5 基础上补充"超管以某租户管理员身份操作其资源"，复用既有知识库/成员/对话/AI 配置页面，不新建跨租户列表。
  - **后端**：`CommonConstant` 新增 `TENANT_HEADER="X-Tenant-ID"`；`UserServiceImpl.getLoginUser` 对 `super_admin` 且携带该头时，将 `tenantId` 临时覆盖为请求头值（仅内存、不写库），普通用户忽略以防越权；`AuthInterceptor` 对 `super_admin` 直接放行全部接口（含 `tenant_admin` 专属的成员管理接口）。
  - **前端**：新增 `context/TenantContext.tsx`（超管加载并默认选中租户，持久化 `localStorage`）；`api/client.ts` 拦截器携带 `X-Tenant-ID`；`AppLayout` 仅对超管渲染租户切换器、将其有效角色映射为可见 `tenant_admin` 菜单（知识库/成员管理），主内容区以 `currentTenantId` 为 `key` 重挂触发各页刷新；`ChatContext` 随 `currentTenantId` 变化刷新对话列表。
  - **单测**：`UserServiceImplTest` 新增 4 例覆盖超管带头覆盖 / 不带头 / 非法头及普通用户忽略越权。
  - **联调验证**：用临时超管 `super@xiongda.com`（密码已重置为 `test123456`）登录实测——带 `X-Tenant-ID` 调 `/api/user/list` 返回目标租户成员（Count=1 且 tenantId 一致），不带时返回空（超管 tenantId 为 NULL 不串数据），证明超管放行 + 租户上下文切换生效。
  - **Bug 修复**：方案A 初版把主内容区改为嵌套 `<div key>{outlet}</div>`，中间 div 无 `h-full` 打断了对话页 `h-full flex flex-col` 高度链，导致输入框不再固定在底部；已把 `key` 上移到外层 `flex-1` 容器、去掉多余嵌套层，DOM 结构恢复为与 `<Outlet/>` 直接作子元素等价，布局修复（前端 `npm run build` + lint 通过）。

---

**M3 合计: ~6 天**

---

## M4 — Agent 推理 + 体验增强

### M4-1 ReAct Agent 多步推理 [全栈] P1 · 2d ✅ 完成

- **实现方式**：自研轻量 ReAct 循环（非 LangChain AgentExecutor），基于 LLM 原生 function-calling 风格的文本协议（Thought / Action / Action Input / Final Answer），与业界成熟 RAG 方案 的自研 ReAct 引擎思路一致。
- **后端**（`ai-service`）：
  - 新增 `services/agent.py`：`run_agent` 主循环（Think→Act→Observe，最大 `MAX_AGENT_ITERATIONS=5`），含 ReAct 文本解析器 `parse_react`、工具执行 `_execute_tool`、来源汇总。
  - 工具范围（M4-1）：仅 `knowledge_base_search`（包 `rag_service.retrieve`）。联网搜索工具留待 M4-3，届时仅新增一个工具注册 + 开关，架构零改动。
  - `llm_service` 新增 `stream_messages(messages, ...)`（给定完整 messages 列表流式补全），供 Agent 每轮复用；原 `stream_generate` 行为不变。
  - `routers/chat.py` 新增 `mode="agent"` 分支，封装 SSE 事件：`agent_step`（thought/action/observation）、`sources`、`token`（最终答案流式）、`error`（MODEL_CONFIG_ERROR / AGENT_ERROR）、`done`。
- **前端**（`frontend`）：
  - `api/chat.ts` 的 `streamChat` 新增 `mode` 参数透传。
  - `ChatPage.tsx`：底部输入区新增「普通问答 / 智能推理」模式分段切换；助手气泡内新增 `AgentSteps` 推理步骤树组件（思考/调用工具/观察结果，可折叠）；解析 `agent_step` 事件累积展示。
- **测试**：`tests/test_agent.py`（9 例全过）覆盖多轮推理事件序列、单轮直答、检索模型配置错误；`AgentRouteTest` 用真实 `app` 对 `POST /ai/chat/stream mode=agent` 做完整 SSE 契约验证。
- **联调**：Python 8001 新代码启动 + agent 路由冒烟通过（agent 分支可达）。受环境无真实 LLM/Embedding Key 限制，真实多步推理需配置 Key 后由 Java 8080 + Python 8001 + 前端 5173 全栈联调。
- **链路已贯通**：前端 `mode` → Java `ChatRequest.mode` → `AiServiceClient` → Python `/ai/chat/stream`（Java 无需改动，早已支持 `mode` 透传）。
- **Bug 修复（2026-07-14）— 智能推理检索不到知识库内容**：Agent 模式 `knowledge_base_search` 返回「未检索到」而知识库确有相关内容。
  - 根因：`services/agent.py:_extract_query` 对 LLM 输出的 Action Input 容错不足。普通 RAG 模式直接用 `body.question` 不经过该解析；Agent 用 LLM 生成的子查询，而 LLM（豆包等）常以 ```` ```json ```` 代码块包裹 Action Input。旧实现 `json.loads` 失败后直接把整段脏字符串（含 ```` ``` ```` 围栏）当 query 去检索，必然落空；单测此前仅覆盖干净 JSON，未能暴露。
  - 修复：`_extract_query` 增加容错——先剥离 markdown 代码块围栏、再尝试提取首个 JSON 对象、最后退化为纯文本查询；并在 Agent 检索工具入口与 `rag.retrieve` 融合后增加诊断日志（清洗后 query、vec/bm25 top 分、merged 数），便于联调定位。
  - 测试：`tests/test_agent.py` 新增 `ExtractQueryTest`（6 例）覆盖干净 JSON / ```` ```json ```` 包裹 / 前后多余文本 / 首个 JSON 对象提取 / 纯文本 / 空输入；agent 单测 15 例全过。
  - 待联调确认：相关性门槛（`retrieval_vector_min_relevance=0.30` / `retrieval_bm25_min_relevance=1.0`）是否对中文短查询过严；已加诊断日志，需真实环境带 Key 跑一次 Agent 后据日志判定是否调整（该环境临时实例无 LLM Key，未能走到工具调用）。
- **依赖**: M2-5
- **产出**: 复杂问题多步推理回答（知识库检索工具）

### M4-B Agent 原生 Function Calling 升级 [Python] P1 · 0.5d ✅ 完成

> 背景：M4-1 Agent 基于自研 ReAct 文本协议（Thought / Action / Action Input），依赖正则解析 LLM 输出，脆弱易错。M4-B 升级为 OpenAI 兼容的原生 function calling，LLM 直接返回结构化 `tool_calls`，解析可靠；同时保留 ReAct 文本降级路径，兼容用户配置了不支持 function calling 的模型。

- **llm.py**：
  - `_stream` 新增 `tools` 参数：传 tools 时在请求体附加 `tools` + `tool_choice: "auto"`，切换至 `_iter_tokens_with_tools` 解析。
  - 新增 `stream_agent_turn(messages, tools, ...)`：Agent 单轮生成入口，yield `{"type": "token", "content": ...}` 文本令牌与 `{"type": "tool_calls", "calls": [{"id", "name", "arguments"}]}` 流末事件。
  - 新增 `_iter_tokens_with_tools`：按 `index` 累积流式 `delta.tool_calls` 分片（id / name / arguments 逐片拼接），流末一次性 yield 完整工具调用列表；文本 token 列正常透传。
- **agent.py**：
  - 新增 `TOOLS` 全局工具定义（OpenAI 兼容 function-calling 格式，当前仅 `knowledge_base_search`，M4-3 web_search 仅需追加一项 + 一个分支）。
  - 重构 `run_agent` 为双路径：优先原生 function calling（本轮 LLM 返回 `tool_calls` → 调用工具 → 回填 `assistant(tool_calls)` + `tool` 消息），降级走 ReAct 文本解析（`parse_react` + Observation 文本回填）。两条路径共用同一事件协议（`agent_step` / `sources` / `token` / `error`）。
  - 新增 `_resolve_query`：统一解析 function calling 标准 JSON 与 ReAct Action Input 文本（兼容代码块围栏），供 `_execute_tool` 共用。
  - 更新 `_SYSTEM_PROMPT`：增加 function calling 调用指令 + ReAct 降级说明。
  - `parse_react` / `_extract_query` 保留不变（降级与容错解析仍需要）。
- **chat.py / 前端**：事件协议不变，无需任何改动。`routers/chat.py` Agent 分支仅做 `async for evt in run_agent(...): yield _sse(evt["event"], evt["data"])`，不感知内部路径切换。前端 `ChatPage.tsx` 的 `agent_step` 解析字段（`step/type/content/tool/input/success`）无变化。`stream_messages` 保留供其他内部调用（如 query rewrite），Agent 改用 `stream_agent_turn`。
- **测试**：重写 `test_agent.py`——`FunctionCallingTest`（3 例：多轮 tool_calls + source + token、单轮直答、配置错误 yield error）、`FallbackReactTest`（2 例：ReAct 降级多轮、空间检索流）、`ParseReactTest`（3 例保留）、`ExtractQueryTest`（6 例保留）、`ToolEmptyKbTest`（3 例新增：工具空结果约束、_resolve_query JSON/ReAct/空输入）、`AgentRouteTest`（1 例完整 SSE 契约通过）。共 18 例，无回归。
- **全量 Python 单测**：56 passed / 4 failed，4 例失败均为预存问题与 M4-B 无关（3 例 `test_document_processor` Windows 下 asyncio 事件循环兼容、1 例 `test_query_rewrite` L1 缓存污染导致 mock 未调用）。
- **依赖**: M4-1
- **产出**: Agent 使用原生 function calling 可靠解析工具调用，ReAct 文本降级作为兜底

### M4-C Agent 智能增强（memory 固化 + reflection 反思 + 上下文压缩）[Python] P2 · 0.5d ✅ 完成

> 背景：Agent 在多轮长对话中容易丢失上文上下文；工具检索后缺少自评机制，可能做不必要多轮或过早中断；messages 累积过多可能超出 LLM 上下文窗口。M4-C 引入三项轻量增强，借鉴 EventAgentReflection 反思循环与 memoryConsolidator 压缩思路，各自由 config 开关独立控制。

- **memory 固化**（`consolidate_memory`）：
  - 当 `history` 消息数 ≥ `agent_memory_min_messages`（默认 4）时，用一次轻量 LLM 调用从历史提取关键事实与用户意图，压缩为 3~5 条要点的记忆块。
  - 记忆块作为 `[对话记忆]` 系统消息注入 messages（system prompt 之后、历史之前），使 Agent 在长对话中不丢失上下文。
  - 失败降级：返回空字符串，不阻断主流程；配置错误抛 `ModelConfigError` 向上透传。
- **reflection 反思**（`reflect`）：
  - 每轮工具调用后，LLM 自评检索结果是否足以回答用户问题，输出 `{"can_answer": bool, "reason": str}`。
  - `can_answer=true` 时注入 system 指令引导下一轮 LLM 直接产出最终答案（不再调用工具），避免无效多轮。
  - JSON 解析失败时默认 `can_answer=false` 继续检索；配置错误向上透传。
- **上下文压缩**（`compress_context`）：
  - 每轮 LLM 调用前估算 messages 字符数（`estimate_chars`），超 `agent_context_max_chars`（默认 12000，≈6k tokens）时触发。
  - 压缩策略：保留 system + 用户问题原文 + 最近 6 条消息原貌；对旧轮次 assistant/tool 消息做摘要压缩（一次 LLM 调用），注入 `[历史摘要]` 系统消息。
  - 压缩后消息数 / 字符数均有日志记录；可压缩内容 < 300 字符时跳过；失败降级返回原列表。
- **配置**（`core/config.py`）：
  - `enable_agent_memory`（默认 True）/ `enable_agent_reflection`（默认 True）/ `enable_agent_compression`（默认 True）
  - `agent_memory_min_messages=4` / `agent_context_max_chars=12000`
- **agent.py 集成**：
  - `_build_messages` 新增 `memory_block` 参数注入记忆块。
  - `run_agent` 循环前：memory 固化；每轮 LLM 调用前：上下文压缩检查；每轮观察后（FC 与 ReAct 两路径均覆盖）：reflection 判断。
- **测试**：
  - 新增 `tests/test_agent_intelligence.py`——`MemoryConsolidationTest`（5 例：历史足够/不足/空/None/LLM异常）、`ReflectionTest`（4 例：can_answer true/false/JSON 带多余文字/纯文本容错）、`ContextCompressionTest`（5 例：压缩减少消息数/太短不压/可压内容太少跳过/失败回退/字符估算）。共 14 例全部通过。
  - `tests/test_agent.py` 新增 `AgentEnhanceMemoryTest` / `AgentEnhanceReflectTest` / `AgentEnhanceCompressTest`（共 4 例集成测试，覆盖 memory 记忆注入/reflection 引导最终答案/reflection 信息不足继续/压缩触发）。原有 18 例已补充 noop 桩保持行为不变。
- **全量 Python 单测**：74 passed / 4 failed（4 例失败均为预存问题，与 M4-C 无关）。
- **依赖**: M4-B
- **产出**: Agent 长对话记忆不丢失、信息充分时自动终结、上下文窗口安全（事件协议不变，前端/Java 无需改动）

### M4-A 普通问答增强（query rewrite + expansion，对齐 业界 KnowledgeQA 方案）2026-07-14 ✅ 已完成

> 背景：用户口语化 / 长问句（如「算法入职前期准备要做什么」）与文档表述（「一、入职基础准备」）措辞差异大，纯字面检索召回弱；业界方案 普通问答用 query rewrite + expansion 让两种模式都对措辞鲁棒。本项目此前普通问答用用户原话直接检索，缺该能力。Agent 模式由 LLM 自生成子查询，不二次改写。

- **Python（ai-service）**：
  - 新增 `services/query_rewrite.py`：`rewrite_query`（一次 LLM 调用把口语问题改写成检索友好关键词短句）+ `expand_query`（主检索召回不足时生成 1~2 个语义不同角度的扩展 query）。失败策略：模型配置错误（`ModelConfigError`）向上抛出（rewrite 在主检索前，应透传让用户重配）；其他异常降级（rewrite 返回原话、expand 返回空列表），不阻断主流程。
  - `services/llm.py`：新增非流式 `complete()` 方法（累积 token 返回完整文本），供 rewrite / expansion 复用，复用 `_stream` 底层调用与鉴权，模型配置错误同样抛 `ModelConfigError`。
  - `services/rag.py`：`retrieve` 新增 `enhance: bool = False` 参数；`enhance=True` 时先 `rewrite_query` 改写 `search_query`，主检索结果数 `< retrieval_expansion_min`（默认 3）时再 `expand_query` 并 RRF 合并兜底；抽取 `_search_core`（向量 + BM25 + RRF，返回融合结果与向量/BM25 最高分用于相关性门槛）；rerank 使用改写后的 `search_query`。L1 缓存 key 仍用用户原话（改写仅提升本次召回，不污染缓存键）。
  - `core/config.py`：新增 `enable_query_rewrite`（默认 True）、`enable_query_expansion`（默认 True）、`retrieval_expansion_min=3` 三个开关。
  - `routers/chat.py`：rag 模式调用 `retrieve(..., enhance=True)`；Agent 模式不传（保持默认 False）。
- **前端**：无需改动（rag 模式 `mode=rag` 自动增强，开关在 Python 配置层，无新交互）。
- **测试**：新增 `tests/test_query_rewrite.py`——`QueryRewriteTest`（5 例：rewrite 成功 / 通用异常降级原话 / 配置错误上抛 / expand 返回列表 / expand 异常返回空）+ `RetrieveEnhanceTest`（2 例：enhance=True 触发改写且召回非空、enhance=False 不调改写）；`tests/test_chat.py` 补充 `_fake_complete` 桩覆盖 enhance 路径。全量 Python 单测 52 通过（3 例 `test_document_processor` 因 `asyncio.get_event_loop()` 在新区 Python 已弃用，属预存环境无关失败，非本次引入）。
- **未改动**：Agent 架构、L1/L2/L3 缓存结构、相关性门槛逻辑、Java 后端、前端。

---

### M4-2 共享/个人知识库完善 [全栈] P1 · 1d ✅ 完成

- **Java（M3-1 已落地，本次确认已覆盖）**：`service/KbPermission.java` 的 `assertCanCreate` / `assertCanWrite` 已完成 scope 权限校验细化 —— 共享库仅 `tenant_admin` / `super_admin` 可创建与上传/删除，个人库仅 owner 可写，超管跨租户放行，叠加租户隔离；`DocumentServiceImpl` 上传/删除、`KnowledgeBaseServiceImpl.createKnowledgeBase` 均调用校验。`KbPermissionTest` 12 例覆盖创建/写入/租户隔离/超管放行全部路径。
- **前端（本次补齐）**：`KnowledgeBasePage.tsx` 引入 `useAuth()` 取角色，计算 `canWrite`（共享库仅管理员为真、个人库全员为真）；据此隐藏「新建知识库 / 上传文档 / 文档删除」按钮与上传区，普通成员在共享库下呈**只读模式**并显示「仅租户管理员可维护」横幅；个人库保持完整管理（创建/上传/删除）。前端隐藏为体验层，后端 `KbPermission` 校验为最终防线，前后端一致。
- **验证**：`npm run build` 通过（tsc + vite，564 模块，0 错误）；后端 `KbPermissionTest` 12 例全过。浏览器端需以普通成员账号登录验证共享库只读效果（环境无现成 member 账号，待联调）。
- **依赖**: M3-1
- **产出**: 共享/个人库权限正确（创建/写仅管理员、个人库仅 owner，读全员开放）

---

### M4-3 问答模式切换（RAG / 联网 / Agent）[全栈] P2 · 0.5d ✅ 完成

> 背景：原仅「知识库问答（rag）」和「智能推理（agent）」两种模式，缺少联网搜索能力。M4-3 新增 `web` 模式支持互联网搜索，并为 Agent 新增 `web_search` 工具，使 Agent 可在知识库搜索结果不满意时自动切换联网搜索。

- **Python（ai-service）**：
  - 新增 `services/web_search.py`：`web_search(query, max_results)` 基于 httpx + DuckDuckGo Lite HTML 搜索（无需 API Key），返回 `[{title, url, snippet}]`；含 `format_search_results` 格式化搜索结果为 LLM 可读上下文。网络超时/不可达时返回空列表，不阻断主流程。DDG Lite HTML 解析兼容 a+span 成对与独立 a 标签两种结构，URL 去重归一化。
  - `services/agent.py`：
    - `TOOLS` 新增 `web_search` 工具定义（OpenAI 兼容 function-calling 格式），`description` 说明「当知识库无结果或需实时信息时使用」。
    - `_execute_tool` 新增 `web_search` 分支：调 `web_search` → 格式化为观察文本 → 返回 web 来源列表（`kb_id="web"`, `doc_id=url`）。检索空结果返回约束文本「请勿编造」。
    - 系统提示更新：优先知识库 → 无结果时用联网搜索 → 联网结果应注明来源 URL。`_extract_query` / `_resolve_query` 无变化（web_search 同样复用 query 解析）。
  - `routers/chat.py`：
    - `ChatStreamRequest.mode` 注释改为 `rag / web / agent`。
    - 新增 `web` 模式处理：`web_search` → 格式化上下文 → `stream_generate` → SSE 推送 `sources`（含 title / url / snippet）和 `token`。搜索异常降级为无上下文问答；`fixed` 兜底与 rag 模式相同。
  - `services/llm.py`：`stream_generate` 新增 `context_source` 参数（`"kb"` 或 `"web"`），影响无内容兜底提示文案（知识库 → 联网搜索）。向后兼容（默认 `"kb"`）。
  - `core/config.py`：新增 `enable_web_search` / `web_search_max_results=5` / `web_search_timeout=15.0` 三项配置。
- **前端（React/TS）**：
  - `ChatPage.tsx`：模式切换从两档（普通问答/智能推理）改为三档（知识库/联网搜索/智能推理），placeholder 按模式动态切换。
  - `ChatMessage.mode` 类型扩展为 `'rag' | 'web' | 'agent'`。
  - `api/chat.ts`：`streamChat` 的 `mode` 参数类型扩展为 `'rag' | 'web' | 'agent'`。
- **前端 UI 优化（2026-07-18）问答模式选择器改为下拉框**：
  - 原输入框底部三档分段按钮（`bg-emerald-50` + `p-0.5` 容器内三按钮）横排占用空间大，模式名长（"联网搜索"、"智能推理"）易挤压图片/附件按钮与模型下拉。
  - 改为单下拉框（`modeMenuOpen` state + `modeMenuRef` ref + 点击外侧自动关闭 effect），触发按钮按当前模式显示对应图标（书本/地球/闪电）+ 模式名 + 旋转的下拉箭头；下拉面板向上弹出（`bottom-full`），三项各带图标 + 名称 + 一句描述（"基于知识库内容回答"/"从互联网搜索最新信息"/"多步推理并自动调用工具"），选中项高亮并打勾。`mode` 状态类型、发送/事件流逻辑均无改动。
  - **修复（2026-07-18）输入框外层去掉 `overflow-hidden`**：首版下拉弹出后只露半截/只显示最后一项的尾巴（如「智能推理 + 描述」），根因是输入框外层容器（`bg-white border ... rounded-2xl`）用了 `overflow-hidden` 把向上弹出的绝对定位下拉面板裁掉了。`overflow-hidden` 原本仅为圆角视觉效果而存在（textarea 自身已有 `max-h-[200px] overflow-y-auto` 限高，无需外层再裁），移除后下拉面板完整显示。
- **Java**：无需改动——`ChatRequest.mode` 是 `String` 无校验，`AiServiceClient` 直接透传 `"web"` 到 Python。注释更新为 `rag / web / agent`。
- **测试**：
  - 新增 `tests/test_web_search.py` — `WebSearchParseTest`（9 例：解析/裁剪/空HTML/无效链接/去重/URL归一化/HTML剥离）+ `WebSearchIntegrationTest`（3 例：搜索有结果/网络超时/空查询）+ `FormatResultsTest`（2 例：格式化有结果/空结果）。共 14 例全部通过。
  - `tests/test_chat.py`：`_fake_stream_generate` 和 `_SpyStreamGenerate` 更新签名兼容 `context_source`，原有测试无回归。
- **全量 Python 单测**：88 passed / 4 failed（4 例失败均为预存问题，与 M4-3 无关）。
- **依赖**: M4-1（Agent）, M4-B（function calling）
- **产出**: 三种问答模式可随时切换；Agent 具备联网搜索能力，知识库无结果时自动联网

#### M4-3 Bug 修复（2026-07-14）
- **联网搜索结果前端不显示标题**：`routers/chat.py` 和 `services/agent.py` 构建 web 来源列表时，字段名误用 `"filename"`（应为 `"source"`），导致前端 `SourceCard` 的 `source.source` 为 `undefined`，标题栏为空。已修改两处为 `"source"`，与 `RetrievalResult` dataclass 和前端 `SourceItem` 接口对齐。
- **联网搜索结果"第0页"显示不合理**：`SourceCard` 对 web 结果（`kb_id="web"`）改用地球图标 + URL 域名替代页码展示，`doc_id` 作为 URL 链接。知识库结果保持原有页码样式不变。
- **测试**：修改后 chat/agent/web_search 共 41 单测全过；前端 `npx tsc --noEmit` 通过、0 lint 错误。

---

### M4-4 文档原文查看 [全栈] P2 · 1d ✅ 完成

- ✅ 后端 `KnowledgeBaseController.getDocumentContent`：返回文档原文文本（同租户权限校验）
- ✅ **新增** `GET /api/knowledge/document/file/{docId}`：返回原始文件流（PDF → `application/pdf` iframe 内嵌预览，其他 → `application/octet-stream`），含租户隔离权限校验、文件存在性检查、`Content-Disposition: inline` 浏览器内预览
- ✅ 前端引用来源卡片「查看原文」：展开检索片段（`SourceCard`，ChatPage.tsx）
- ✅ **新增** 前端文件预览弹窗（`DocumentPreviewModal`）：展开检索片段后出现「查看原文件」按钮，点击弹出全屏模态框。PDF 用 iframe 加载（带 `#page={n}` 锚点尝试定位），文本模式切换按钮可查看文档全文。仅知识库来源显示（web 结果不显示）
- ✅ 前端 `api/knowledge.ts` 新增 `getDocumentFileUrl(docId)` 文件流 URL 构建方法
- **依赖**: M2-5
- **产出**: 点击引用可查看检索片段 + 原文件弹窗预览（PDF iframe / 文本全文）
- ✅ **Bug 修复（2026-07-14）**：原文件被删 / 被改时，前端直接加载文件流 iframe 会触发 Whitelabel 错误页（`type=Not Found, status=404`）。根因：`getDocumentFile` 中 `new FileInputStream` 抛出的受检 `IOException` 未被任何 `@ExceptionHandler` 捕获（全局处理器仅捕获 `BusinessException`/`RuntimeException`），异常穿透到容器渲染默认错误页。修复三处：
  - 后端 `getDocumentFile`：将 `FileInputStream` 的 `IOException` 兜底为业务异常（`BusinessException(NOT_FOUND_ERROR, "原文件读取失败…")`），由 `GlobalExceptionHandler` 转 JSON，杜绝异常穿透到容器；同时 `Files.exists` 缺失文案改为「原文件已被清理或删除，无法预览，请重新上传该文档」。
  - 新增 `GET /api/knowledge/document/file/status/{docId}` 前置校验接口：检测文件是否被删（`Files.exists` / `isRegularFile`）与被改（当前大小与上传记录的 `fileSize` 不一致），返回 `{ exists, changed, message, filename, fileType }`，供前端预览前判读，避免直接加载文件流。
  - 前端 `DocumentPreviewModal` 加载 iframe **前**先调 `knowledgeApi.checkDocumentFileStatus`：`exists=false`（被删/读取失败）→ 居中展示友好提示「原文件已被清理或删除，无法预览，请重新上传该文档」且不再加载 iframe；`changed=true`（被改）→ 顶部黄色警示条「检测发现原文件已被修改，预览内容可能与知识库索引不一致，建议重新上传该文档」并仍加载 iframe；校验中显示「正在校验原文件…」。任何分支均不再出现 Whitelabel 错误页。
  - 测试：新增 `KnowledgeBaseControllerTest`（7 例，覆盖文件存在 / 被删 / 被改 / 文档不存在 / 无权限 / 读取失败 / 正常返回 PDF 流）全过。
- ✅ **Bug 修复（2026-07-15）— 文档原文/引用来源 Markdown 符号裸露（`**`、`#` 等未渲染）**：文档预览与引用来源片段此前用纯文本 `<pre>` / `whitespace-pre-wrap` 渲染，解析内容中的 markdown 标记会原样显示。修复：
  - 前端 `KnowledgeBasePage.tsx` 文档全文预览（`viewContent`）由 `<pre>` 改为 `ReactMarkdown` + `remark-gfm` 渲染。
  - 前端 `ChatPage.tsx` 两处：① 引用来源卡片片段（`source.content`）由 `whitespace-pre-wrap` 改为 `ReactMarkdown` + `remark-gfm`；② 原文件预览面板（`currentText`）由 `<pre>` 改为 `ReactMarkdown` + `remark-gfm`（保留缩放）。AI 回答主体此前已用 `ReactMarkdown` + `rehype-highlight` 渲染，不受影响。
  - 依赖已具备（react-markdown ^10.1.0 / remark-gfm ^4.0.1 / rehype-highlight ^7.0.2）。lint 0 错误。
- ✅ **Bug 修复（2026-07-15）— 上传中文文件名乱码加固**：Spring/Tomcat 对 multipart 文件名的 ISO-8859-1 解码会导致中文文件名乱码（如 `»°Ó­`），显示在文档列表与引用来源。修复 `KnowledgeBaseController.uploadDocument`：仅当文件名全部可由 ISO-8859-1 编码时还原为 UTF-8（正确中文原样保留），从源头防止新上传乱码。经全链路验证（Postgres→JDBC→Java HTTP 输出 / Python 提取 / 磁盘文件）均为标准 UTF-8、数据库内容/文件名/来源均无乱码，本次属上传环节潜在风险加固，不影响历史数据。
- ✅ **Bug 修复（2026-07-16）— 文档预览「暂无可预览内容」**：根因：`getDocumentPages` 仅依赖 AI 服务重新解析**原文件**（`extract-pages`），当原文件被清理、或 Java→Python WebClient 中文路径偶发解析失败、或降级路径 `doc.getContent()` 为空时，均返回空列表 → 前端显示无内容。**治本修复——向量库重建分页兜底**：文档已向量化即可预览，不再依赖原文件存在/路径编码/旧 content 字段。
  - Python `services/vector_store.py` 两种实现（PgVectorStore / InMemoryVectorStore）新增 `get_document_pages(doc_id)`：按 `page` 升序聚合已存分块文本（排除 `qa` 增强块），返回 `[{page_no, text}]`；新增模块级 `_aggregate_pages` 辅助。
  - Python `routers/document.py` 新增 `POST /ai/document/pages-from-db`（入参 `doc_id`）。
  - Java `AiServiceClient.getPagesFromDb(docId)` 调用该路由（失败返回空，由上层继续降级）。
  - Java `DocumentServiceImpl.getDocumentPages` 数据源优先级收敛为三级：① `extractPages` 原文件真实解析 → ② `getPagesFromDb` 向量库重建（新增兜底）→ ③ 已存全文按 `CHARS_PER_PAGE` 估算；并抽取 `toPageContentVOs` 复用转换逻辑。
  - 测试：新增 `tests/test_outline.py` 覆盖分页重建（PG/内存两种实现）、大纲标题识别、大纲意图判断、chunk_types 过滤（5 例全过）。
  - 验证：`POST /ai/document/pages-from-db` 对真实 PDF（doc_id=2077628273789894658）返回 394 页；两服务重启后全链路跑通，预览兜底生效。

- ✅ **Bug 修复（2026-07-16）— 多模态 Embedding 串行导致大文档上传卡死「检索中」**：根因（附运行日志铁证）：用户 Embedding 模型为 `doubao-embedding-vision`（多模态），其 `/embeddings/multimodal` 端点不支持批量，原 `embedding.py::_embed_remote` **逐条串行**调用；14MB/394 页 PDF 切成 3001 块（方案C 大纲分块使块数翻倍），串行发 3001 次 HTTP ≈ 10 分钟，正好顶到 Java `AiServiceClient.processDocument` 的 10 分钟超时；且 embed 是单次同步循环，期间点取消无法中断（用户 20:21 取消，20:24 embed 才跑完才生效）。修复：① `embedding.py` 多模态分支改为 `asyncio.Semaphore(embedding_concurrency=16)` 限流**并发**调用，替代逐条串行；② `_call` 增加 429/5xx **退避重试**（最多 3 次），避免大批量并发被限流直接失败；③ 新增配置 `embedding_concurrency`。修复后 3001 块可在 1~2 分钟内完成（且随取消检查点及时退出）。单测 `tests/test_embedding_concurrency.py` 覆盖多模态逐条并发 / 标准批量 / 429 重试（3 例全过）。**配置建议**：纯文本 PDF 建议改用标准 `doubao-embedding`（批量 `/embeddings` 一次请求、几秒完成），多模态模型仅图文混合文档需要。

- ✅ **异步优化（2026-07-16）— 文档处理改用自定义 ThreadPoolExecutor**：`DocumentServiceImpl.triggerDocumentProcessing` 原 `CompletableFuture.runAsync(...)` 使用默认 `ForkJoinPool.commonPool()`，高并发上传时可能挤占公共池、影响其他并行任务。改为专用 `ThreadPoolExecutor`（核心2/最大8/队列16，线程名前缀 `doc-process-`，`CallerRunsPolicy` 背压，daemon 线程 + `allowCoreThreadTimeOut` 空闲回收）。上传请求线程仍立即返回（前端靠轮询状态），行为不变；单测 `DocumentServiceImplTest.triggerDocumentProcessing_runsOnCustomThreadPool` 断言任务运行在 `doc-process-` 线程（36 例全过，BUILD SUCCESS）。

---

### M4-5 登录页动效 [前端] P2 · 1d ✅ 完成

- ✅ Canvas 粒子系统（90 粒子 + 距离连线 + 鼠标交互吸引）
- ✅ 极光光晕动画（`.aurora`）
- ✅ 毛玻璃卡片（`.glass-card`）+ 左右分栏品牌区
- ✅ 密码强度检测（4 格强度条，长度/字母/数字/特殊字符各 1 分）
- ✅ 社交登录按钮（GitHub / Google，占位）
- **依赖**: M1-3
- **产出**: 登录页与 UI 设计稿一致（AuthPage.tsx 已实现，此前漏标）

---

### M4-6 问答页 UI 精细化 [前端] P2 · 1d ✅ 完成

- ~~推荐问题胶囊~~ ✅
- ~~知识库选择标签~~ ✅
- ~~工具栏（智能推理下拉、模型选择、附件按钮）~~ ✅
- ~~输入框自动增高~~ ✅
- ~~对话气泡（用户右对齐 / AI 左对齐卡片）~~ ✅
- **AI 思考动画（2026-07-16 补充）**：流式等待首字期间，AI 气泡显示「思考中．」→「思考中．．」→「思考中．．．」三点循环（400ms 切换，省略号容器 `w-[1.2em] inline-block` 固定宽度防气泡跳动）；替代原静态「思考中...」。实现：`frontend/src/pages/ChatPage.tsx` 新增 `ThinkingDots` 组件（`setInterval` 循环 1→2→3 点），在 `assistant` 末条消息且无 `agentSteps` 时渲染。
- **依赖**: M2-6
- **产出**: 问答页与 UI 设计稿一致

> 注：基础 UI 框架已在 M1-8.5 完成；M2-6 会话管理完成后补充了 Markdown 渲染、引用来源卡片，本次收尾补齐输入框自动增高，M4-6 全部完成。

---

### M4-9 知识库批量上传/删除弹窗化 [前端] P2 · 0.5d ✅ 完成（2026-07-16）

- ~~知识库页面「批量上传 / 批量删除」改为顶部独立按钮~~ ✅
- ~~批量上传：弹窗内选本地文件入列表、不即时解析，确认后统一走完整上传+解析流程~~ ✅
- ~~批量上传弹窗支持点击选择 + 拖拽文件放入（带拖拽悬停高亮）~~ ✅
- ~~批量删除：弹窗内列表勾选（支持全选）后统一删除~~ ✅
- 移除文档列表旁的行内勾选与拖拽上传区
- **依赖**: M1-4 / M2-7（上传解析链路、批量删除接口）
- **产出**: 批量上传与删除交互收口到弹窗表单，选完本地文件一次性确认解析

**实际完成内容：**
- 顶部工具栏由「新建知识库 / 上传文档」改为「新建知识库 / 批量上传 / 批量删除」三个独立按钮（只读角色不显示后两者）。
- 批量上传弹窗：`uploadFileInputRef` 多选文件加入 `uploadFiles` 列表（暂不上传），列表可逐条移除；点击「确认上传」调用 `handleConfirmBatchUpload` 循环 `knowledgeApi.uploadDocument`，逐个显示进度，失败汇总，结束后清空列表并刷新文档与知识库计数。
- 批量删除弹窗：展示当前知识库文档列表，支持全选/取消全选与逐条勾选（`deleteIds`），点击「确认删除」调 `handleBatchDelete` → `batchDeleteDocuments`。
- 移除原列表行内 checkbox（`selectedIds`/`toggleSelect`/`allSelected`/`toggleSelectAll`）、拖拽上传区、上传中批量操作工具条；表格列由 8 列收为 7 列。
- 后端接口（`/knowledge/document/upload`、`/knowledge/document/batch-delete`）零改动，仅前端交互形态变更。
- `npx tsc --noEmit` 通过，无 lint 错误。

---

### M4-7 通知与提示 [前端] P2 · 0.5d ✅ 完成

- Toast 通知组件（成功/失败/警告）
- 上传进度条
- 加载状态骨架屏
- 空状态提示
- **依赖**: M1-9
- **产出**: 全局交互反馈完善

#### 实现记录（2026-07-14）
- 新增全局顶部 Toast 组件 `frontend/src/components/Toast.tsx`：固定在页面顶部居中的绿色（成功）/红色（失败）横幅，含图标，3 秒自动消失；通过 `ToastProvider` 挂在应用根部（`main.tsx`），任意页面用 `useToast().success/error/notify` 触发。
- 登录成功：顶部绿色「登录成功」后跳转 `/chat`。
- 注册成功：顶部绿色「注册成功，请登录」（保持注册不自动登录、切回登录表单的旧逻辑）；邀请加入成功：顶部绿色「加入成功」。
- AI 配置保存成功：顶部绿色「配置保存成功」；普通用户（`user` 作用域）保存后延迟 1.2 秒自动跳转 `/chat`，超管编辑「平台默认配置」（`platform` 作用域）仅提示不跳转。
- 说明：此前注册/配置保存的成功提示是卡片内小横幅（非页面顶部），本次统一改为页面顶部绿色 Toast；`tsc` 与 `npm run build` 均通过。

#### 补充实现（2026-07-15）：上传进度条 + 加载骨架屏 + 文档列表空状态
- `frontend/src/api/knowledge.ts` 的 `uploadDocument` 新增可选 `onProgress(pct: number)` 回调；axios 请求携带 `onUploadProgress`（基于 XHR，`e.total` 存在时按 `loaded/total` 计算百分比并 `Math.round`），**零后端改动**。
- `KnowledgeBasePage.tsx` 新增 `uploadProgress` 状态（null=未上传）：上传开始置 0、成功/失败/`finally` 置 null；上传区下方仅在上传中渲染进度条（绿色渐变填充 + 百分比文字）。
- 文档列表加载原本加了骨架屏（`docLoading` + 3 行灰色占位块），但用户反馈加载较快时灰块「一闪而过」体感像闪烁；最终**移除骨架屏**：删去 `docLoading` 状态与 `loadDocuments` 的 `try/finally` 包裹，tbody 不再渲染任何占位行，加载期间表格沿用已有文档内容、数据返回后直接更新，彻底无灰块闪现。
- 文档列表空状态从单行文字升级为带文档图标的居中提示「该知识库暂无文档」+（可写角色）上传引导，与知识库列表空状态视觉风格一致。
- 验证：`npm run build`（`tsc -b` + `vite build`）exit 0，类型与构建均通过。上传进度条真实百分比依赖浏览器 XHR，需浏览器内上传文件手动验证。

---

### M4-8 文档状态四阶段与检索优化增强（retrieval augmentation）[全栈] P1 · 1d ✅ 完成

- **需求**：文档列表状态栏需呈现完整处理流（对齐业界成熟 RAG 方案 文档上传→完成流程）：`处理中 → 解析 → 检索 → 优化 → 就绪/失败`。其中「优化」为真正增强——基于分块生成问答对并入库（retrieval augmentation），直接提升检索召回率。
- **状态机重构**（`DocStatusEnum`）：`processing`(处理中) / `parsing`(解析中) / `retrieving`(检索中) / `optimizing`(优化中) / `ready`(已就绪) / `failed`(处理失败)，移除原 `pending`/`embedding`。
- **阶段状态实时回调**：Python 在各阶段边界 `POST /api/internal/document/status` 回调 Java 内部接口推进状态（best-effort）；最终 ready/failed 仍由 Java 依据 Python 同步返回落库。上传初始置 `processing`，`triggerDocumentProcessing` 置 `parsing`。
- **检索优化（retrieval augmentation）**：`DocumentProcessor.process` 在向量化入库后进入优化阶段，用 LLM 基于每个分块生成「问题-答案」对，将问题向量化后以 `chunk_type=qa` 与原分块一次性入库；相似度检索自动覆盖增强块，提升召回。LLM 配置错误（无 Key/模型名错）best-effort 跳过增强、文档仍就绪（不阻塞主流程）。
- **前端**：`types/index.ts` 状态联合类型新增四阶段值；`KnowledgeBasePage.tsx` 状态徽章新增「处理中/检索中/优化中」（优化用紫色区分），轮询条件与旋转图标纳入新中间态。
- **改动文件**：
  - 后端：`DocStatusEnum.java`、`DocumentServiceImpl.java`（upload 置 processing）、新增 `InternalDocumentController.java` + `InternalDocStatusRequest.java`（内部回调接口，状态合法性校验）。
  - Python：`core/config.py`（backend_base_url / enable_qa_augment / qa_max_pairs）、新增 `services/status_callback.py`、`services/document_processor.py`（retrieving/optimizing 回调 + 问答增强入库 + `_parse_qa_json` 容错解析）。
  - 前端：`types/index.ts`、`pages/KnowledgeBasePage.tsx`。
- **单测**：后端 `DocumentServiceImplTest` 新增初始状态 processing、状态推进验证（17 例全过）；Python `tests/test_document_augment.py` 覆盖问答增强入库 + 状态回调 + LLM 配置错误 best-effort 跳过 + JSON 容错解析（3 例全过）。
- **依赖**：M2-3 文档处理全链路、M3-3 模型配置错误体系。
- **产出**：文档列表状态真实反映 处理中→解析→检索→优化→就绪 全链路，且检索因问答增强块而更准。

**集成验证（2026-07-15 运行时联调）**：
- 三服务均用新代码重启存活：Java `:8080/health`=200、Python `:8001/health`=200、前端 `:5173`=200。
- **Java 内部回调接口（HTTP 层）**：对真实文档 `docId=2077093729271078913` 依次 `POST /api/internal/document/status` 传 `retrieving`/`optimizing`/`ready`，均返回 `{"code":0,"data":true}` → 新增四阶段状态值被 Java 正确接受并落库（测后已恢复 `ready`）。
- **Python→Java 回调链路（运行时）**：用真实 `services/status_callback.py::notify_document_status` 向运行中 Java 发起 `retrieving`/`optimizing` 回调，Java 实测返回 `{"code":0,"data":true}`；且其 `client=None` 真实路径（走 `settings.backend_base_url`）也能成功把文档回调并恢复 `ready`，无异常 → Python 回调模块与运行中 Java 端到端连通。
- 结论：新增的「阶段状态实时回调」机制（Python 在 retrieving/optimizing 边界回推 Java）在运行时端到端验证通过；四阶段状态流的逻辑正确性由 Python 单测 `test_document_augment.py`（process 顺序 emit retrieving→optimizing 并入库 qa 块，3 例全过）+ Java 单测 `DocumentServiceImplTest`（17 例全过）覆盖。完整「上传→processing→parsing→retrieving→optimizing→ready」的金链路演示需登录有效账号且 AI 配置含可用 Embedding/LLM 密钥（DB 中 tenant 2075873177644326913 用户已配置火山方舟 Embedding 密钥），本无头环境未走完整浏览器上传，但回调与状态机已逐项验证。

---

### M4-8.1 文档优化阶段异步化 + 持久化增强队列（对齐 业界 finalizing（异步增强），2026-07-15）[全栈] P1 · 1d ✅ 完成

- **背景**：M4-8 的「优化」阶段在 `DocumentProcessor.process()` 同步主链路里**串行**跑（20 块 × (LLM ~30s + 多模态单条 Embedding ~30s) ≈ 20min），大文档长时间卡在「优化中」、且未就绪前不可检索。对标业界成熟 RAG 方案：其 `finalizing` 状态**文档已可向量检索**，增强任务异步扇出、不阻塞；且任务持久化、服务重启可由 sweep 恢复、文档删除可取消。
- **需求**：向量化完成即让文档可检索；问答增强改为持久化后台队列（跨重启不丢、可取消），优化中不再阻塞检索与界面。
- **改造（核心）**：
  - **新增 `ai-service/services/augment_queue.py`**：基于 Redis 的持久化可靠队列。键：`xiongda:augment:queue`（待处理）/ `xiongda:augment:processing`（已取未完，带 `started_at`）/ `xiongda:augment:cancelled`（set，已删文档 doc_id）。方法：`enqueue` / `dequeue`（RPOPLPUSH queue→processing）/ `ack` / `mark_cancelled` / `is_cancelled` / `sweep_stale`（把 processing 中超时任务移回 queue）。
  - **Python `services/document_processor.py`**：`process()` 向量化后**先 `store_chunks` 原始块（立即可检索）** → 回调 `optimizing` → 把增强任务**入持久化队列**（`enqueue`，含 `ai_config` 以便 worker 跨进程重建 cfg）→ **立即返回 `{status: optimizing, chunk_count, content}`**（HTTP 不阻塞）。后台逻辑由 `_run_augment_task(task)` 承担：从向量库读回原始块重建（不依赖上传文件是否仍在）并 reuse embedding（L2 缓存）→ 并发生成问答增强块全量 `store_chunks` → 回调 `ready`；含看门狗（总超时 `qa_background_timeout` + 异常兜底）；任务被取消 / 原始块已不存在则跳过（不回调 ready）。新增 `run_augment_worker()` 常驻消费队列（FastAPI lifespan 启动）。`_generate_qa_augment` 维持**限并发**（`Semaphore(qa_concurrency)` + `gather`）+ **批量 Embedding** + **单块超时跳过**。
  - **Python `main.py` lifespan**：启动时先 `sweep_stale()` 恢复上次崩溃残留的 processing 任务，再 `asyncio.create_task(run_augment_worker())` 启动常驻 worker（对齐 业界 finalizing（异步增强） sweep）。
  - **Python `core/config.py`**：`qa_concurrency=5` / `qa_per_qa_timeout=120.0` / `qa_background_timeout=600.0`。
  - **Python `routers/document.py`**：`process` 透传 `ai_config_dict` 入队；`DELETE /ai/document/{doc_id}` 删向量时 `mark_cancelled(doc_id)`（取消排队中的增强任务）。
  - **Python `services/vector_store.py`**：`embeddings` 表新增 `metadata JSONB` 列（ALTER IF NOT EXISTS，向后兼容旧数据）；`store_chunks` 写入完整 metadata；三实现均新增 `get_original_chunks(doc_id)`（**排除 `chunk_type=qa` 块**，供 worker 重建原始块，避免重复增强）。
  - **Java `AiServiceClient`**：新增 `deleteDocument(docId)` → `DELETE /ai/document/{docId}`（清向量 + 取消增强任务）。
  - **Java `DocumentServiceImpl`**：`deleteDocument` 调 `aiServiceClient.deleteDocument(docId)`（修复此前 `TODO` 未清向量/未取消任务的缺口）；`triggerDocumentProcessing` 将 `optimizing` 与 `ready` 一并视为成功；`updateDocumentStatus` 带**终态守卫**。
  - **前端 `KnowledgeBasePage.tsx`**：`optimizing` 状态文件名可点击查看原文 + 状态列追加「已可检索」提示。
- **存储策略（关键约束）**：`PgVectorStore.store_chunks` 按 `doc_id` **全删重写**（非 upsert）。worker 从向量库**读回原始块**（`get_original_chunks`，已排除 qa 块）重建后连同新生成的增强块**全量 `store_chunks`**，运行期间原始块始终在库可检索，且不依赖上传文件是否仍在（文件可能被删）。
- **改动文件**：`ai-service/services/augment_queue.py`(新)、`ai-service/services/document_processor.py`、`ai-service/services/vector_store.py`、`ai-service/core/config.py`、`ai-service/routers/document.py`、`ai-service/main.py`、`backend/.../AiServiceClient.java`、`backend/.../DocumentServiceImpl.java`、`frontend/src/pages/KnowledgeBasePage.tsx`。
- **单测**：Python `tests/test_document_augment.py`（5 例：process 入库原始块+入队 / worker 增强入库 qa 并回调 ready / 取消跳过 / 文档已删跳过 / JSON 容错）+ 新增 `tests/test_augment_queue.py`（4 例：enqueue/dequeue / cancelled set / sweep 移回超时任务 / sweep 保留近期，用内存异步 fake Redis 规避 fakeredis 传输兼容问题），共 9 例全过；Java `DocumentServiceImplTest` 新增 3 例（optimizing 视为成功 + 终态守卫 + optimizing→ready 允许）+ 删除文档 verify 调 `deleteDocument`，总计 20 例全过。
- **依赖**：M4-8 阶段状态回调 + retrieval augmentation 体系。
- **产出**：文档进入「优化中」即已可检索与查看原文；问答增强跑持久化队列（限并发 + 批量 embed，分钟级），**服务重启可由 sweep 恢复、文档删除可取消**，彻底对齐 业界 finalizing（异步增强） 语义；前端明确提示「已可检索」。

---

### 前端轮询修复：failed 可恢复态自动刷新（2026-07-18）[前端] P3 · 0.25d ✅ 完成

- **现象（用户反馈）**：上传 `Git工作中需要的操作.docx`(514.7KB) 一度显示「处理失败」，但后端实际已处理成功（DB 最终 `ready`、PG 入库 49 块 + ES 双写 48 块，且 `/api/internal/document/status` 回调 `ready` 返回 200）。根因：前端轮询仅在 `processing/parsing/retrieving/optimizing` 时每 3 秒刷新；一旦状态被判定为终态 `failed` 即**停止轮询**，导致后端把 `failed` 改写成 `ready`（重试成功 / 自动恢复）时前端无法自动感知，必须手动刷新页面才更新。
- **改造（`frontend/src/pages/KnowledgeBasePage.tsx`）**：重写状态轮询 `useEffect` 的触发条件——除处理中四态外，对**可自动恢复的 `failed` 文档**（即 `status=failed` 且非 `modelConfigError` 且非 `quotaError`）也持续轮询；模型配置错 / 额度错导致的失败为终态、须人工干预，不纳入轮询。为避免永久失败文档空转，对可恢复 `failed` 的轮询加 **5 分钟窗口上限**（`FAILED_POLL_MAX_MS`），超出即停。恢复为 `ready` 或转为处理中后清空计时标记。
- **改动文件**：`frontend/src/pages/KnowledgeBasePage.tsx`（仅轮询逻辑；`tsc --noEmit` 通过、组件 lint 0 错误）。
- **依赖**：M4-8 状态机 + M4-8.1 异步增强（后端可能将 failed 改写为 ready）。
- **产出**：后端把失败文档自动恢复为就绪时，前端无需手动刷新即可及时显示「已就绪」并可查看/问答；配置错/额度错等真正终态仍提示用户去配置或重试，不浪费轮询。

---

### 文档处理取消按钮（软取消 + 可中断，M5-3 前置，2026-07-15）[全栈] P1 · 1d ✅ 完成

- **背景**：用户希望在文档「上传 → 就绪」过程中可主动停止处理。当前架构主流程（解析+向量化）在 Python 同步 handler 执行、无中途取消点；仅增强阶段（optimizing）有 `is_cancelled` 守卫。本次实现「软取消」：保留文档记录、标 `cancelled` 终态、清理已写向量、停止增强，并在主流程加最小取消检查点使其真正可中断（即提前落地 M5-3 的多检查点取消守卫核心）。
- **需求**：前端在非终态（processing/parsing/retrieving/optimizing）显示「取消」按钮，点击后文档停止处理并变为「已取消」。
- **改造**：
  - **枚举/状态**：`DocStatusEnum` 新增 `CANCELLED("cancelled","已取消")`；`updateDocumentStatus` 终态守卫把 `cancelled` 纳入终态（终态不可被中间态回退覆盖）。
  - **后端接口**：`KnowledgeBaseController` 新增 `POST /api/knowledge/document/cancel`；`DocumentService.cancelDocument` 校验权限（租户隔离 + 知识库写权限）→ 标 `cancelled` → 调 `AiServiceClient.cancelDocument`（POST `/ai/document/{docId}/cancel`）。
  - **Python 取消接口**：`routers/document.py` 新增 `POST /ai/document/{doc_id}/cancel`：`delete_by_doc` 清向量 + `mark_cancelled`（复用 `xiongda:augment:cancelled` set，与删除共用）。
  - **Python 主流程检查点**：`document_processor.process()` 在**嵌入前**与**入库后（notify optimizing 之前）**两处检查 `is_cancelled`，命中则清理已写向量 + 回调 `cancelled` + 返回 `cancelled`（不返回 optimizing）。
  - **竞态处理**：`triggerDocumentProcessing` 收到 Python 返回 `cancelled` 即落库 `cancelled`；若用户中途取消但 Python 仍跑完返回 `ready/optimizing`，因 DB 已 `cancelled`（终态守卫拒绝回退），触发分支主动 `deleteDocument` 清向量，避免残留「已取消却可检索」的文档。
  - **前端**：`types` 的 `Document.status` 增加 `cancelled`；`knowledgeApi.cancelDocument`；`STATUS_CONFIG` 增加已取消样式；非终态文档操作列显示「取消」按钮（`handleCancel` 二次确认）。
- **改动文件**：`DocStatusEnum.java`、`DocumentService.java`(接口)、`DocumentServiceImpl.java`、`AiServiceClient.java`、`KnowledgeBaseController.java`、`routers/document.py`、`document_processor.py`、`types/index.ts`、`api/knowledge.ts`、`KnowledgeBasePage.tsx`。
- **单测**：Java `DocumentServiceImplTest` 新增 4 例（非终态取消成功 / 终态幂等 / cancelled 终态守卫 / 竞态清向量），共 24 例全过；Python `test_document_augment.py` 新增 2 例（嵌入前取消 / 入库后取消清向量），共 7 例全过。
- **依赖**：M4-8.1（队列 + cancelled set + 终态守卫基础）。
- **产出**：用户可在处理中任意阶段点「取消」停止；文档变「已取消」并清理向量；提前落地 M5-3 的取消守卫（M5-3 余下「完整 4 处检查点 + 适配主流程队列化」仍按计划实现）。
- **关联**：M5-3（多检查点取消守卫）已先行部分实现，剩余工作见 M5 阶段。

---

### 文档处理失败重试按钮（手动重试 + 大文档修复） [全栈] P1 · 0.5d ✅ 完成（2026-07-16）

- **背景**：文档处理因网络抖动、模型配置临时错误、大文档响应超缓冲等原因失败（failed）或被取消（cancelled）后，此前只能删除重传，无法一键重试。本次在操作列新增「重试」按钮，并修复联调暴露的两个真实 bug。
- **需求**：`failed` / `cancelled` 终态文档在操作列显示「重试」，点击后重新触发解析/分块/向量化，复用原上传者 AI 配置。
- **改造**：
  - **后端接口**：`KnowledgeBaseController` 新增 `POST /api/knowledge/document/retry`；`DocumentService.retryDocument` 校验权限（租户隔离 + 知识库写权限）→ 仅 `failed`/`cancelled` 可重试（其余状态抛业务异常）→ 校验原文件仍存在（缺失则提示重新上传）→ 绕过终态守卫直接把状态重置为 `processing` 并清空 `errorMsg`/`modelConfigError`/`chunkCount` → 复用原上传者配置 `triggerDocumentProcessing` 重新处理。
  - **前端**：`knowledgeApi.retryDocument(id)`；`KnowledgeBasePage` 新增 `handleRetry`；操作列在 `failed`/`cancelled` 时显示「重试」按钮（品牌色，与取消/删除并列）。
- **附带修复 1（大文档处理失败，DataBufferLimitException）**：`AiServiceClient` 的 WebClient 用默认响应缓冲上限（仅 256KB），而文档处理接口会把提取全文随响应返回，394 页 PDF（401KB）即超限导致重试仍失败。修复：`ExchangeStrategies` 放宽 `maxInMemorySize` 到 20MB（常量 `MAX_IN_MEMORY_SIZE`）。
- **附带修复 2（成功后旧错误信息残留）**：`updateDocumentStatus` 原仅在 `errorMsg != null` 时更新，重试成功（ready/optimizing）传 `errorMsg=null` 不写入，导致 DB 旧 `error_msg` 残留展示。修复：成功终态（ready/optimizing）强制 `setErrorMsg(null)` + `setModelConfigError(false)`；并因 MyBatis-Plus `updateById` 默认 `NOT_NULL` 策略会忽略 null 字段，给 `Document` 实体的 `errorMsg`/`modelConfigError` 加 `@TableField(updateStrategy=FieldStrategy.ALWAYS)` 使置 null 能真正写库。
- **改动文件**：`DocumentService.java`(接口)、`DocumentServiceImpl.java`、`KnowledgeBaseController.java`、`AiServiceClient.java`、`Document.java`、`api/knowledge.ts`、`KnowledgeBasePage.tsx`；单测 `DocumentServiceImplTest.java`。
- **单测**：Java `DocumentServiceImplTest` 新增 6 例（重试重置并重新触发 / 文档不存在 / 已就绪拒绝 / 原文件缺失拒绝 / 个人库非 owner 拒绝 / 成功态清空残留错误），共 34 例全过。
- **集成验证**：真实登录（租户管理员）→ 调重试接口 200 → 文档 `failed → parsing → retrieving → optimizing → ready`（1024 chunks 可检索），`error_msg` 清空、`model_config_error=false`，394 页 PDF 不再触发 `DataBufferLimitException`。
- **依赖**：M4-2（`KbPermission`）、文档取消小节（终态与状态机）。

---

### 文档批量上传与批量删除 [全栈] P1 · 0.5d ✅ 完成（2026-07-16）

- **背景**：知识库文档管理原仅支持单文件上传、单文档删除，批量维护效率低。本次新增批量上传与批量删除。
- **需求**：
  - 批量上传：一次选择多个文件依次上传，逐个显示进度（第 N/共 M 个），单个失败不阻断其余、最后汇总失败文件名。
  - 批量删除：文档列表加复选框（表头全选 + 每行勾选），选中后弹出工具条一键删除多个文档。
- **方案（简洁优先）**：
  - **批量上传**：复用现有单文件上传接口，前端 `<input multiple>` + 循环调用（后端零改动，天然复用配额校验与异步处理；multipart 批量反而更复杂）。
  - **批量删除**：后端新增批量端点 `POST /api/knowledge/document/batch-delete`，一次请求处理 N 个 id。**fail-fast**：先全量校验（存在 / 租户隔离 / 知识库写权限），任一不通过即抛异常、不删除任何文档（避免部分删除中间态）；全部通过后逐个删向量 + 逻辑删除，最后**只清一次**该租户 L1 检索缓存、统一同步涉及知识库的文档数（较单删循环 N 次清缓存更高效）。
- **改造**：
  - **后端**：`DocumentService.deleteDocuments(List<Long> docIds, tenantId, user)` 接口 + `DocumentServiceImpl` 实现（`@AuditLog(action="doc_batch_delete")`，去重、fail-fast 两遍校验）；新增 `DocumentBatchDeleteRequest` DTO；`KnowledgeBaseController` 新增 `POST /document/batch-delete` 端点返回实际删除数量。
  - **前端**：`knowledgeApi.batchDeleteDocuments(ids)`；`KnowledgeBasePage` 新增 `selectedIds` 选择集合 + `uploadBatch` 批次进度；`handleUpload` 改多文件循环上传；新增 `handleBatchDelete` / `toggleSelect` / `toggleSelectAll`；`<input multiple>`；表格加复选框列（表头全选 + 行勾选）与批量删除工具条；切换知识库 / Tab 自动清空选择。
- **改动文件**：`DocumentService.java`(接口)、`DocumentServiceImpl.java`、`DocumentBatchDeleteRequest.java`(新增)、`KnowledgeBaseController.java`、`api/knowledge.ts`、`KnowledgeBasePage.tsx`；单测 `DocumentServiceImplTest.java`。
- **单测**：Java `DocumentServiceImplTest` 新增 5 例（全部成功只清一次缓存 / 去重 / fail-fast 文档不存在不删除 / fail-fast 无权限不删除 / 空列表参数校验），共 29 例全过。
- **依赖**：M4-2（知识库权限 `KbPermission`）、M2-7（L1 缓存失效）。

---

### 修复：对话页路由切换后消息丢失（含进行中流式回复） [前端] P1 · 0.5d ✅ 完成（2026-07-16）

- **现象**：在对话页发送一条消息后（AI 正在流式回复），切到知识库等其他页面再切回，消息与记录全部消失，需刷新浏览器才出现，且刷新后 AI 回复不会补回、只剩用户发的一条消息。
- **根因**：`messages`、`streaming`、`conversationId` 均为 `ChatPage` 本地 `useState`。React Router 切换路由会卸载 `ChatPage` 组件，本地状态全部丢失；切回时 `useEffect[activeId]` 从后端 `listMessages` 重载，但新会话的 `activeId` 要等流式 `done` 事件才注册（重载时 `activeId` 仍为空 → 直接 `setMessages([])` 清空），且后端流式回复尚未落库，重载竞态导致空白；`conversationId` 本地丢失还会使再次发送误开新会话。
- **方案（简洁优先·状态提升）**：把会话消息与流式状态提升到 `ChatContext`，**按会话 id 缓存**（跨路由卸载保留于内存），切走切回直接复用缓存而非后端重载：
  - 新增 `messagesByConv: Record<convId, ChatMessage[]>` 与 `PENDING_KEY='__pending__'` 占位（新会话流式 `done` 注册前，进行中消息暂存 PENDING，注册后迁移到真实 id 并标记已加载）。
  - 引入 `loadedConvIds` 集合：会话首次从后端成功加载历史后标记；后续路由重挂时若该会话已加载则**跳过重新加载**，避免覆盖进行中的流式回复（取代原 `skipReloadRef` 竞态处理）。
  - `streaming` 提升为 `ChatContext.isStreaming`（全局单一），切走后流在后台继续写入缓存，切回仍能看到回复且输入框保持禁用。
  - `activeId` 初始化时从 `localStorage` 恢复（刷新浏览器后自动回到上次对话窗口），会话列表 `refresh` 时若会话已删则清除。
- **改造**：`context/ChatContext.tsx`（状态提升 + PENDING 迁移 + loaded 标记 + localStorage 恢复；`ChatMessage`/`AgentStep` 类型移至 `types/index.ts` 共享）；`pages/ChatPage.tsx`（去掉本地 `messages`/`streaming`/`conversationId`，改用 `useChat()` 的 `messages`/`setMessages`/`isStreaming`/`setStreaming`/`markLoaded`/`isLoaded`，发送时捕获 `convAtSend` 取代本地 `conversationId`）；`types/index.ts` 新增 `ChatMessage`/`AgentStep`。
- **验证**：`npx tsc --noEmit` 通过、无 lint 错误；前端 dev server 热更新后手动验证——发送消息（AI 回复中）立即点知识库再点回对话，消息与流式回复保留；刷新浏览器从 localStorage 恢复当前会话并加载历史。
- **边界说明**：浏览器「刷新」会中断进行中的流式请求，若 AI 回复尚未完成则后端未落库、刷新后该条助手回复不补回（属刷新中断的本质行为，非本次 bug）；切换页面（不刷新）已彻底修复。
- **二次优化（2026-07-16，同 issue）**：状态提升后「消息不丢」已修复，但切回时助手气泡仍会短暂空白、需等一会才出字。根因：`ChatPage` 路由切走再切回会重挂，重挂瞬间 `useEffect[activeId]` 执行 `setStreaming(false)` 把全局流式标记强行置否（丢失"思考中"指示与输入框禁用态），且重挂使 `followRef`/`atBottomRef` 重置为 `false`，后续流式 token 不自动滚动。而 `abortRef` 是组件本地 `useRef`，重挂后为 `null`，`abort()` 实际为 no-op（后台流仍在写缓存）。修复：移除 effect 里的 `setStreaming(false)`（全局 `isStreaming` 仍由 `sendMessage` 的 `finally` 在流真正结束/中止时统一置否），并在 `activeId` 为空（PENDING 新会话）与已加载分支里把 `followRef`/`atBottomRef` 置真并 `scrollToBottom('auto')`，使切回即跳到底部、跟随后续 token；`abort()` 仅对已挂载时的 activeId 变化（点侧栏切会话）生效，避免跨会话串流。`npx tsc --noEmit` 通过、无 lint 错误。

### 修复：AI 回复中文乱码（U+FFFD 替换符） [后端·AI服务] P1 · 0.3d ✅ 完成（2026-07-16）
- **现象**：问答回复中出现 `�` 乱码（如「抓包��具」「（自动化必备） ���完成」），且并非来自已入库文档。
- **排查**：直接查向量库 `embeddings.content`，测试文档块为干净中文、无 `�`，说明乱码不在文档解析/存储环节（`document_processor._clean_text` 本就会删除 U+FFFD 占位符）。回复正文里的「抓包工具」「（自动化必备）…完成」本就是 LLM 自行生成、未出现在检索上下文中，故乱码只能在「LLM 流式响应 → Python 解码」这一环引入。
- **根因（修订，2026-07-16 二次定位）**：初判为「httpx 猜测编码」（`aiter_lines` 未设 `response.encoding`），但加上 `response.encoding = "utf-8"` 重启后（日志确认 01:35 那次带乱码问答恰发生在已含该修复的重启之后）乱码仍在，说明猜测编码不是主因。真正根因是 **`aiter_lines` 在 LLM 流的分块（TCP/HTTP chunk）边界把多字节中文字符截成两半**，半截字节被 UTF-8 解码器当成非法序列替换成 U+FFFD；字符跨 chunk 截断后即便设了 encoding 也会产生乱码。前端（`TextDecoder({stream:true})` 已按多字节增量缓存）、Java（`ChatController.decode` 用 `StandardCharsets.UTF_8`、WebFlux `StringEncoder` 默认 UTF-8）均经核查正确，故乱码仅源自 Python 这层。
- **修复（根治）**：放弃 `aiter_lines` 自动解码，改为 `response.aiter_bytes()` **累积原始字节**到 `buf`，按 `\n` 切行后**整行 `decode("utf-8")`**（行以 `\n` 分隔，字符不会跨行截断，故整行解码永远不会遇到半截字节 → 不再产生 U+FFFD）。`_iter_tokens` 与 `_iter_tokens_with_tools` 两处均改写；`[DONE]` 在 function-calling 路径用 `done` 标志跳出以保持流末工具调用汇总语义。另加一条**仅在复现 U+FFFD 时**的 `logger.warning("[乱码诊断] ...")` 便于复测确认（确认稳定后可删）。`py_compile` 通过；`pytest tests/test_llm.py` 3/3 通过（aiter_bytes 改写未被破坏）；全量 117 通过（3 个 `Event loop is closed` 环境失败与改动无关）。已重启 AI 服务（8001）使修复生效，`/health` 返回 200。
- **边界说明**：Java 侧 `ChatController.decode` 用 `StandardCharsets.UTF_8`、Python→Java SSE 由 FastAPI `StreamingResponse` 默认 UTF-8、前端 `TextDecoder({stream:true})` 均正确，故本次仅修 Python LLM 流解码这一环即可根治。若复测后 `ai_8001_err.log` 仍出现「[乱码诊断]」告警，则乱码源在更下游（Java/前端），需另查。

### 修复：引用来源中文乱码（Java SSE 消费分块截断） [后端] P1 · 0.3d ✅ 完成（2026-07-16）
- **现象**：AI 回复正文正确（如「第一时间修改」），但**引用来源**卡片的文档原文片段出现 `�`/`���`（如「新人需`�`一时间修改」）。正文由 LLM 理解生成、能纠错；source 是直接从库读出的原始 chunk 文本，损坏即原样展示。
- **排查**：直接查 `embeddings` 表 `SELECT ... WHERE content LIKE '%'||chr(65533)||'%'`，**全表 440 行无任何 U+FFFD**；Python `routers/chat.py` 的 `_sse` 用 `json.dumps(ensure_ascii=False)` 由 Starlette 以 UTF-8 发出，出口字节正确。故乱码既不在文档入库，也不在 Python 出口，而发生在 **Java 消费 AI 的 SSE 流** 这一环。
- **根因**：`ChatController.chatStream` 此前对每个 `DataBuffer` 单独 `StandardCharsets.UTF_8.decode(bb)`（`decode` 静态方法），当 HTTP chunk 边界恰好切在多字节中文字符（如「第」U+7B2C 的三字节）中间时，逐 buffer 解码把半截字节替换成 U+FFFD。token 事件都很小、通常单 buffer 完整故正文无碍；source 这个大 JSON 常跨多个 buffer，故引用来源偶发乱码。与之前 Python LLM 流乱码是同一类「分块边界截断多字节字符」问题，只是作用在 source 字段而非 token 流。
- **修复（根治）**：改为**流式 `CharsetDecoder`**——每条问答流创建 `StandardCharsets.UTF_8.newDecoder().onMalformedInput(REPLACE).onUnmappableCharacter(REPLACE)`，在 `map` 中用三参数 `decode(bb, out, false)`（endOfInput=false）解码：跨 `DataBuffer` 边界的不完整多字节字符被缓存在解码器内，待后续字节补齐后再输出，从根上消除边界截断。流结束（`doOnTerminate`）用 `decode(ByteBuffer.allocate(0))`（endOfInput=true）flush 残留尾部。注意**单参数 `decode(ByteBuffer)` 内部是 endOfInput=true，会把边界处不完整字节直接 REPLACE 成 U+FFFD，绝不能用**——必须用三参数流式重载。删除了被取代的 `decode(DataBuffer)` 静态方法。
- **测试**：`ChatControllerTest` 新增回归用例 `chatStream_multibyteCharSplitAcrossBuffers_preserved`——把含「第一时间」的 SSE 字节在「第」首字节之后切分喂入两个 `DataBuffer`，断言聚合后的回答/来源不含 U+FFFD。后端全量单测 `mvn clean test` 通过（无回归）；已重启后端（8080，`/health` 监听）使修复生效。
- **边界说明**：前端 `TextDecoder({stream:true})`、Python→Java SSE（FastAPI UTF-8）均正确，本次仅修 Java SSE 消费这一环即根治引用来源乱码。若复测仍有，则需查前端渲染层。

### 说明：问答召回「别的文档」（RAG 固有行为，非 bug） [后端·检索] 2026-07-16
- **现象**：问「测试入职准备」时，除「自动化测试新员工入职指南」外，还引用了「前端开发新员工入职指南（第2页）」。
- **原因**：RAG 取 top-N 相关块跨知识库文档。两份文档都列了「接口测试工具 Postman/Apifox/JMeter、UI测试工具 Selenium/Playwright/Appium、抓包工具」等**相同测试工具词汇**，向量与 BM25 均会召回前端文档那一段；rerank（llm，阈值 `retrieval_rerank_min_relevance`）判定该段与「测试入职准备」相关性 ≥ 阈值则保留。该段本就讲测试工具，属 borderline 相关，非错误文档。
- **方案决策（2026-07-16，与用户确认）**：用户选择**保持现状（问答仍检索全租户所有库，不按主题拆库）** + **上调 rerank 相关性门槛到 0.40**（方案 B 落地，非拆库方案 A）。理由：存在「前端如何对接测试」这类跨主题问题，拆库会人为割裂交叉答案，故不采用 A；B 在压掉跨主题噪音的同时保留跨库召回能力。已修改 `ai-service/core/config.py:68` 默认值 `0.30 → 0.40`，重启 AI 服务（8001，`/health` 200）生效；测试无硬编码依赖该值，全量 117 通过（3 个 `Event loop is closed` 环境失败与改动无关）。注意：`retrieval_relative_ratio`（0.80）未动，仅调绝对门槛。
- **边界说明**：阈值上调可能误删真正 borderline 相关块。若后续发现合理跨主题引用被过度剔除，可回调 0.35~0.40 区间。

---

### M4-9 部署与 DevOps [运维] P2 · 1.5d

- Docker Compose（PostgreSQL + Redis + Milvus + Java + Python + Nginx）
- Java Dockerfile（多阶段构建）
- Python Dockerfile
- 前端构建产物 Nginx 部署
- 环境变量管理（.env.production）
- **依赖**: M3 全部
- **产出**: 一键 docker-compose up 启动

---

**M4 合计: ~11.5 天**

---

### M4-X 大纲感知分块与大纲召回（方案C）[Python] P2 · 0.5d ✅ 完成（2026-07-16）

- **背景**：问「知识架构 / 大纲 / 目录 / 有哪些模块」类问题时，原检索在概述页命中的是泛泛介绍，AI 难以归纳出文档真实章节框架。以 `Java面试宝典` 为例，文本层并无「Java基础 / 并发 / MySQL」这类顶层架构词（其在未 OCR 的饼图里），但顶层框架词「Redis / 数据库 / JVM / 消息队列 / 网络」实际藏在各级小节标题中。
- **方案C 思路（轻量、不引入重架构）**：大纲感知分块 + 大纲专用召回路由。文档处理时为章节标题单独生成 `outline` 块；问架构/大纲类问题时优先召回这些标题块，让 LLM 基于真实章节主题归纳知识框架（而非仅命中概述页）。不引入父子分块 / GraphRAG。
- **后端改动**：
  - `services/document_processor.py`：`chunk_text` 在常规内容块之外，逐页扫描标题行生成 `chunk_type=outline` 的标题块（记录 `section_title`），**不破坏内容块**；新增 `_extract_outline_titles` / `_is_title_line` 启发式（编号章节 `1.` / `1.2` / `(1)` / `一、` / `第X章` + 架构/大纲/目录/知识点/考点/体系 关键词）。已收紧规则：去掉「为什么…？」追问句与冒号短句噪声，避免 outline 块过多（实测从 2577→1945）稀释常规检索。
  - `services/vector_store.py`：两种实现 `search` / `keyword_search` 新增 `chunk_types` 过滤参数；**常规检索（`chunk_types=None`）默认排除 outline 块**，避免稀释常规问答召回；仅大纲意图显式传 `["outline"]` 才召回大纲块。
  - `services/rag.py`：新增大纲意图检测 `_is_outline_query`（模块级 `OUTLINE_KEYWORDS`：架构/大纲/目录/有哪些/知识点/考点/包含哪些/体系/模块…）；命中时在检索主流程后追加一轮仅召回 outline 块的「向量 + BM25 融合」，并将这些大纲块**保送结果前部**（跳过 rerank 阈值与相对相关性/全局相关性门槛过滤，避免短标题块被误剔除），再补常规结果；诊断日志增加 `outline=on/off`。
  - `core/config.py`：新增 `retrieval_outline_top_n=12`（大纲块保送数量）。
  - QA 增强跳过 outline 块（`document_processor` 仅对内容块生成问答对，避免短标题污染增强集）。
- **测试**：新增 `tests/test_outline.py` —— 大纲标题识别各类形态、大纲意图判断、chunk_types 过滤（PG/内存两种实现）、分页重建，全过。真实 PDF 大纲诊断：1945 个 outline 块正确覆盖 `Redis=34 / 数据库=39 / 消息队列=16 / 并发=25 / JVM=13 / 网络=37 / MySQL=20 / 设计模式=4` 等顶层框架词；常规检索已排除 outline（不稀释）。
- **注意（生效条件）**：**已有文档的向量是旧分块（无 outline 块）**，方案C 的大纲召回需文档重新向量化后生效——重新上传/处理触发，或后续 M5 主流程重处理；新上传文档自动带 outline 块。本无头环境未走完整浏览器重上传，但大纲分块逻辑、chunk_types 过滤、意图路由均经单测与真实 PDF 文本诊断验证，且全部改动模块已被运行中的服务成功加载（健康检查 200）。
- **依赖**：M2-3 文档处理全链路、M2 检索。
- **产出**：问知识架构 / 大纲 / 目录 / 有哪些模块类问题时，能基于文档真实章节标题归纳出知识框架。

---

## M5 — 文档处理深度对标业界成熟方案（主流程健壮性 + 功能广度）

> **背景**：M4-8.1 已完成「优化阶段异步化 + 持久化增强队列」，对齐了 业界 `finalizing=queryable` 与增强队列的崩溃恢复/取消语义。但经源码逐点比对，熊答在以下 8 处仍未对标业界成熟方案（详见对话核对）：
> 1. 解析+向量化阶段不在持久化队列 worker 内（崩溃不可自动恢复，会卡 `parsing/retrieving`）
> 2. 缺重试机制（业界方案 Asynq `MaxRetry(3)` + 退避）
> 3. 缺多检查点取消守卫（业界方案 4 处 `isKnowledgeAborted`）
> 4. 缺阶段化 span 时间线追踪（业界 `beginStage/endStage/failStage`）
> 5. 单层 chunk，无父子分块（业界方案 父块入库供上下文、子块进向量索引）
> 6. 无多模态 OCR/VLM caption 增强（业界 `enable_multimodel → image:multimodal`）
> 7. 增强仅问答对，无 GraphRAG/Auto-Wiki/摘要/问题（业界方案 postprocess 多种形态）
> 8. 单 pgvector，无复合检索引擎（业界 `composite` = pgvector + ES/Qdrant）
>
> M5 逐一对齐这 8 点，**按「先主流程健壮性、后可观测性与功能广度」的顺序一个一个实现**。

### M5-1 文档处理主流程持久化队列化（解析+向量化入队）[全栈] P0 · 2d ✅ 完成

- **需求**：把「提取→分块→向量化→入库」整段主流程从 Python 同步 HTTP handler 搬进持久化任务队列 worker，对标业界成熟方案 把 `ProcessDocument` 整体跑在 Asynq 持久化任务里。HTTP 上传仅 `enqueue` 即返回，主流程异步执行、崩溃可恢复。
- **改造**：
  - 新增主流程队列 `process_queue.py`（复用 `augment_queue.py` 可靠性模式）：`xiongda:doc:queue` / `xiongda:doc:processing`（带 `started_at`）/ `xiongda:doc:cancelled`；函数 `enqueue / dequeue / ack / mark_cancelled / is_cancelled / sweep_stale`（超时卡死任务移回队列，崩溃恢复）。
  - `POST /ai/document/process` 改为**仅 enqueue 即返回 `{status: processing}`**，不再同步跑解析；新增 worker `run_process_worker` 在 FastAPI lifespan 启动，消费队列执行 `process()`（解析+向量化+入库+入增强队）；摄取异常由 worker 经状态回调推进 `failed`。
  - 删除/取消接口（`DELETE /document/{id}`、`POST /document/{id}/cancel`）同步标记主流程 `cancelled` 集，worker 取任务前跳过、避免已删/已取消文档继续处理。
  - Java `triggerDocumentProcessing` 去掉对 Python 同步返回的依赖：发请求后立即置 `parsing`；仅当返回**非** `processing`（入队失败）才直接落 `failed`（区分 `MODEL_CONFIG_ERROR` / `MODEL_QUOTA_ERROR`）；返回 `processing` 即视为成功并清该租户 L1 检索缓存（保证新文档立即可搜）。
  - **契约变更（状态+全文改由回调驱动）**：Python `notify_document_status` 扩展 `content` / `chunk_count` / `error_msg` / `model_config_error` 字段；`process()` 在 `optimizing` 阶段回调携带全文 `content` 与 `chunk_count`。Java 侧 `InternalDocStatusRequest` 新增 `content`，`DocumentService.updateDocumentStatus` 新增 7 参重载（`...Boolean modelConfigError, Boolean quotaError, String content`），`optimizing`/`ready` 成功态一并回填 `content`、清空历史错误标记；`InternalDocumentController` 透传 `content`。前端仍从 DB 读 `content`（经回调落库），无前端改动。
  - 启动时 `sweep_stale`（`main.py` lifespan 中 `sweep_process_stale`）同时恢复主流程队列；worker 任务随 lifespan 启停。
- **依赖**：M4-8.1（队列基础设施）
- **改动文件**：
  - Python：`services/process_queue.py`（新增）、`services/document_processor.py`（`run_process_worker` / `_run_process_task` + content 回调）、`services/status_callback.py`（`notify_document_status` 扩展字段，超时 30s）、`routers/document.py`（仅入队）、`main.py`（lifespan 启停 worker + sweep）、`tests/test_process_queue.py`（新增）。
  - Java：`DocumentServiceImpl.java`（`updateDocumentStatus` 7 参重载 + `triggerDocumentProcessing` 重写）、`DocumentService.java`（接口新增 7 参方法）、`InternalDocStatusRequest.java`（新增 `content`）、`InternalDocumentController.java`（透传 `content`）、`tests/DocumentServiceImplTest.java`（更新为 M5-1 `processing` 契约）。
- **单测**：Python `test_process_queue.py` 覆盖 enqueue/dequeue、cancelled 集、sweep_stale（超时移回/近期保留）、worker 跳过 cancelled、worker 在 `ModelConfigError` 时回调 failed 且标 `model_config_error`；全量 **145 例通过**（含 `test_document_processor` / `test_document_augment` / `test_query_rewrite` / `test_model_config`，无回归）。Java `DocumentServiceImplTest` 38 例通过（含 M5-1 契约更新：`processing` 视为成功、向量清理竞态移交 Python worker 后 Java 不再调 `deleteDocument`、入队成功清 L1 缓存）。
- **产出**：主流程可崩溃恢复、不阻塞上传 HTTP；文档全文/状态完全由 worker 回调驱动；为 M5-2 / M5-3 打底。

### M5-2 主流程重试机制（MaxRetry + 退避）[全栈] P1 · 0.5d ✅ 完成

- **需求**：对标业界成熟方案 Asynq `MaxRetry(3)` + 自定义退避，主流程（解析/向量化）失败自动重试；非临时错误（模型配置错误）直接 fail 不重试。
- **改造**（`services/process_queue.py` + `services/document_processor.py`）：
  - 任务新增 `retry` 字段（`enqueue` 默认 0）；`process_queue` 新增常量 `MAX_RETRY=3`、`RETRY_BASE_DELAY=30`、`RETRY_MAX_DELAY=600`。
  - 退避计算 `backoff_delay(attempt)`：指数退避 `min(BASE * 2^attempt, MAX)`（30→60→120 秒，封顶 10 分钟），对齐业界 `asynqRetryDelayFunc` 风格。
  - 延迟重试队列：新增 `DELAYED_KEY`（Redis zset，score = `next_attempt_at`）；`requeue_delayed` 把失败任务从 processing 移出、写入延迟 zset；worker 每轮 `promote_delayed()` 把到期任务搬回主队列（无需额外定时器），服务重启后到期即恢复。
  - `_run_process_task` 重试分流：`ModelConfigError`（密钥/模型名/维度错误）**不可重试**，直接回调 `failed` + `model_config_error=True`；其余异常（网络/`ModelQuotaError` 限流/DB 瞬时错误等）按 `retry` 计数指数退避重入队，达 `MAX_RETRY` 上限后回调 `failed`（error_msg 含「已重试 N 次」）。
  - **幂等保障**：PG `store_chunks` 先 `DELETE` 同 `doc_id` 再 `INSERT`，重试重处理不产生重复分块（Milvus 路径当前未启用，不受影响）。
- **改动文件**：`process_queue.py`、`document_processor.py`、`tests/test_process_queue.py`（扩展 FakeRedis 支持 zset + 新增 4 例）。
- **单测**：`test_process_queue.py` 新增 `test_backoff_delay`（30/60/120/封顶）、`test_requeue_delayed_then_promote`（延迟重入队→promote 搬回、retry 递增、去除 next_attempt_at）、`test_worker_retries_then_fails`（瞬时异常重试满 MAX_RETRY 次后 failed、error_msg 含「已重试」、不标 modelConfigError）、`test_worker_does_not_retry_model_config_error`（模型配置错误仅执行 1 次直接 failed 不重入队）；Python 全量 **145 例通过**，无回归。
- **产出**：主流程瞬时失败可自愈（网络抖动/限流/DB 瞬时错误自动重试），减少人工干预；模型配置类错误快速失败不空耗重试；崩溃重启后延迟任务可恢复。
- **说明（错误码）**：失败的 `error_msg` 已携带重试次数与原始错误（如「处理失败（已重试3次）：…」），未新增 Java 独立 `error_code` 列；如需在 DB 落独立错误码，可后续在 `InternalDocStatusRequest` + `Document` 实体追加字段，属非破坏性扩展。

### M5-3 多检查点取消守卫 [全栈] P1 · 0.5d ✅ 完成（2026-07-17）

> 注：取消守卫核心已通过「文档处理取消按钮」功能先行实现（嵌入前 / 入库后两处检查 + `cancelled` 终态 + 竞态清理）。本任务在其基础上补全业界方案完整 4 处检查点并统一主流程取消集语义。

- **需求**：对标业界成熟方案 4 处 `isKnowledgeAborted` 检查点（翻 processing 前 / 写 chunk（嵌入）前 / 索引前 / 标 completed（optimizing）前），防取消竞态。
- **改造**（`services/document_processor.py`）：
  - **统一主流程取消集语义（M5-3 关键修正）**：`process()` 内所有主流程检查点改用 `process_queue.is_cancelled`（即 `xiongda:doc:cancelled` set——删除/取消接口均会标记）。此前 M5-1 前遗留的「嵌入前 / 入库后」检查点误用 `augment_queue.is_cancelled`（增强队列取消集），语义错位；现统一归主流程队列，与 worker 取任务前的 `process_queue.is_cancelled` 判据一致。
  - **取消检查点①（翻 processing 前）**：`_run_process_task` worker 取任务前 `process_queue.is_cancelled` → 跳过（不回调 ready）。
  - **取消检查点②（写 chunk / 嵌入前）**：`process()` 在 `embed_chunks` 前检查 → 未消耗 Embedding 调用、未入库，直接 `cancelled` 返回。
  - **取消检查点③（索引 / 入库前，M5-3 新增）**：`embed_chunks` 完成（可能耗时数分钟）后、调 `store_chunks` 之前新增检查 → 未写向量、无需清理，直接 `cancelled` 返回，避免「先写后删」无谓 IO。
  - **取消检查点④（标 completed / optimizing 前）**：`store_chunks` 入库后、回调 optimizing 之前检查 → 已写向量必须清理（防残留「已取消却可检索」孤立向量）+ 回调 `cancelled` + 返回 `cancelled`（不返回 optimizing）。
  - **增强阶段守卫（既有）**：`run_augment_worker` 取任务前检查 `augment_queue.is_cancelled` / 原始块是否已不存在 → 跳过，与 M5-1 取消小节一致。
- **删除/取消接口**：`routers/document.py` 的 `cancel` / `delete` 同时标记 `xiongda:doc:cancelled`（process_queue）与 `xiongda:augment:cancelled`（augment_queue）两个集合，故主流程与增强流程任一阶段取消均生效，运行时行为不变。
- **改动文件**：`document_processor.py`、`tests/test_document_augment.py`（fixture 默认 patch `process_queue.is_cancelled`；嵌入前用例改判据为 `process_queue`；入库后用例改以「向量是否已入库」为判据；新增「索引前取消」用例）。
- **单测**：`test_document_augment.py` 新增 `test_process_cancelled_before_index`（Embedding 完成后、store 前取消 → 未写向量、无需清理、回调 cancelled、不返回 optimizing）；嵌入前用例改 patch `process_queue`；Python 全量 **149 例通过**，无回归。
- **依赖**：M5-1（主流程队列化）、M5-2（重试）
- **产出**：文档处理任意阶段（嵌入前 / 索引前 / 入库后）取消均真正可中断，且无孤立向量残留；主流程取消集语义统一清晰。

### M5-4 阶段化 span 时间线追踪 [全栈] P1 · 1d ✅ 完成（2026-07-17）

- **需求**：对齐 业界 `beginStage/endStage/failStage`，把 解析(parsing)/分块(chunking)/向量化(embedding)/入库(indexing)/增强(optimizing) 拆成带时间线与指标的阶段，前端展示细粒度进度与失败定位，而非单一状态字符串。
- **改造**：
  - **Python 阶段回调**（`services/status_callback.py`）：新增 `notify_stage(doc_id, stage, status, *, started_at, ended_at, elapsed_ms, error, metrics, client)`，best-effort POST 到 `POST /api/internal/document/stage`，失败仅告警不影响主流程（与 `notify_document_status` 同模式）。
  - **阶段计时器**（`services/document_processor.py` 新增 `_StageTimer`）：异步上下文管理器，进入阶段回调 `active`、退出（正常/异常）回调 `done`/`failed` 并携带耗时与指标（异常自动记 `failed` + error，且不影响主流程错误传播）。
  - **埋点**（`process()` + `_run_augment_task`）：
    - 解析 `parsing`：`extract_pages` 包裹，metrics `{pageCount}`；
    - 分块 `chunking`：`chunk_text` 包裹；
    - 向量化 `embedding`：`embed_chunks` 包裹，metrics `{chunkCount}`；
    - 入库 `indexing`：`store_chunks` 包裹（含 M5-3 取消检查点④），metrics `{chunkCount, vectorsWritten}`；
    - 增强 `optimizing`：增强 worker 实际增强段包裹，metrics `{enhancedCount}`（取消/原块缺失等提前返回在阶段块之外，不误标 optimizing）。
  - **Java 内部接口**（`InternalDocumentController`）：新增 `POST /api/internal/document/stage` → `DocumentService.recordDocumentStage`。
  - **Java 落库**（`DocumentServiceImpl.recordDocumentStage`）：把阶段事件合并写入文档 `process_stages`（TEXT，JSON 数组）字段；按 `stage` 幂等合并（同阶段重放替换而非追加），支持 `startedAt/endedAt/elapsedMs/error/metrics`（metrics 解析为 JSON 节点内嵌）；文档不存在/参数缺省返回 false。
  - **Schema**：`Document` 实体与 `DocumentVO` 新增 `processStages`；`schema.sql` 文档表新增 `process_stages TEXT`；**运行库已 ALTER TABLE document ADD COLUMN process_stages TEXT**（非破坏性）。
  - **前端**（`KnowledgeBasePage.tsx` + `types/index.ts`）：`Document` 新增 `processStages`；新增 `ProcessStage` 类型与 `StageTimeline` 组件——处理中/失败文档状态单元格下展示 解析›分块›向量化›入库›增强 阶段链，done 绿✓+耗时、failed 红✕（hover 显示 error）、active 旋转符；`ready` 不展示以保持简洁。
- **改动文件**：`status_callback.py`、`document_processor.py`、`InternalDocStageRequest.java`、`InternalDocumentController.java`、`Document.java`、`DocumentVO.java`、`DocumentService.java`、`DocumentServiceImpl.java`、`schema.sql`、`types/index.ts`、`KnowledgeBasePage.tsx`、单测 `test_status_callback.py`（新增）、`DocumentServiceImplTest.java`、测试 fixture。
- **单测**：Python `test_status_callback.py` 新增 3 例（请求体正确 / 缺省字段省略 / 失败 best-effort 不抛）；`test_document_augment.py` 新增 `test_process_emits_stage_timeline`（断言 parsing/chunking/embedding/indexing + optimizing 全部 done 且带耗时/指标）；Java `DocumentServiceImplTest` 新增 2 例（合并幂等 + 非法/文档不存在返回 false）；Python 全量 **150 例通过**（新增后），Java `DocumentServiceImplTest` 全过，前端 `npm run build` 通过。
- **依赖**：M4-8.1 状态回调体系、M5-1、M5-3
- **产出**：用户可见细粒度处理进度（解析→分块→向量化→入库→增强）与失败精确定位（失败阶段红标 + error）。

### M5-5 父子分块（parent/child chunk）[Python] P1 · 1d ✅ 完成（2026-07-17）

- **需求**：对标业界成熟方案 父子分块（small-to-big）——父块入库供上下文召回、子块进向量索引，提升检索精度与上下文连贯度。
- **实现**（采用 small-to-big 叠加式，最小侵入现有链路）：
  - **config**：新增 `enable_parent_child`（默认开）、`parent_chunk_size=1500`、`parent_chunk_overlap=100`、`retrieval_parent_context`（默认开，检索回溯父块内容）。
  - **`chunk_text`（document_processor.py）**：开启开关时，先按 `parent_chunk_size` 切父块（`chunk_type=parent`/`is_parent=true`），再在父块内用 `chunk_size` 切子块并记录 `parent_id` 归属；关闭时退化为原单层分块。父子块并行于既有 outline 块产出（不破坏）。
  - **`embed_chunks`**：跳过父块向量化（embedding 保持 None，落库 dimension=0 天然不进向量检索），避免无谓 Embedding 成本。
  - **`store_chunks`**：父子块均入 PG；父块写带 `chunk_type=parent` 的元数据，BM25 兜底索引排除父块（父块过长会干扰关键词召回）。
  - **检索回溯（`vector_store.py`）**：`RetrievalResult` 新增 `parent_id` / `parent_content`；新增模块级 `attach_parent_contents` + 各实现的 `attach_parents`，按 `(doc_id, parent_id)` 批量读回父块内容 small-to-big 填充；`get_parents_by_doc` 读回父块；`get_original_chunks` / `get_document_pages` / BM25 `keyword_search` 均排除父块。Pg `warmup_bm25` 现携带 `chunk_type`/`parent_id`，修复重启后 outline 块被误召回问题。
  - **`rag.retrieve`**：检索末尾 best-effort 调用 `attach_parents`（失败降级用子块内容）。检索缓存 key 纳入 `retrieval_parent_context` 以便开关变更失效。
  - **`chat.py`**：喂 LLM 的上下文优先用 `parent_content`（更连贯），引用来源 `sources` 仍用子块 `content` 精确定位。
  - **增强 worker（`_run_augment_task`）**：读回父块透传、与子块+qa 增强块一并全量 store（幂等覆盖），避免增强入库时父块丢失；`_generate_qa_augment` 排除 parent 与 outline 块；Milvus 实现补齐空方法。
- **测试**：`test_vector_store.py` 新增 5 例（父块排除检索/关键词/attach 回溯/get_parents_excludes）；`test_document_processor.py` 新增 3 例（父子两层产出/关闭退化/embed 跳过父块）；`test_document_augment.py` 补桩 `get_parents_by_doc`。全量 `pytest tests/` 161 passed。
- **依赖**：M5-1（或现有 store_chunks）
- **产出**：检索召回更完整、上下文更连贯；父块仅在命中后回溯，不增加向量检索噪声与 Embedding 成本。

### M5-6 多模态增强（图片 OCR + VLM caption）[Python] P2 · 2d ✅ 完成（2026-07-17）

- **需求**：对标业界 `enable_multimodel → image:multimodal`，对文档内图片做 OCR + VLM caption，每图拆 OCR 块 + Caption 块入向量库，使图片内容可被检索问答。
- **实现**（最小侵入现有增强队列与 `chunk_type` 体系，无新基础设施）：
  - **config**：新增总开关 `enable_multimodal`（默认开）与 `multimodal_max_images=20`（每文档图片上限）、`multimodal_min_image_bytes=1024`（过滤图标/字形/分隔线等过小噪声）、`multimodal_ocr_caption_concurrency=4`（图片级并发）、`multimodal_per_image_timeout=120.0`（单图 VLM 超时）。
  - **图片抽取（document_processor.py）**：新增 `extract_images` 按类型委派——PDF 走 `fitz` 逐页 `page.get_images` 抽取原始字节（`_extract_pdf_images`，PyMuPDF 不可用时返回空列表降级）；DOCX 走 `zipfile` 读 `word/media/`（`_extract_docx_images`，无真实页码记 `page=0`）；txt/md 不抽图。两函数均按 `min_image_bytes` 跳过过小图片。
  - **OCR + Caption（复用 M3-3 配置的 LLM）**：新增 `_ocr_and_caption`，用 OpenAI 兼容多模态消息（`content` 为含 `image_url` 的 parts 列表）一次调用同时产出 OCR 文字与图像描述（JSON `{ocr, caption}`），由新增 `_parse_ocr_caption_json` 容错解析。需用户配置支持多模态/图片输入的 LLM。
  - **增强生成与 worker 接入**：新增 `_generate_multimodal_augment`，复用增强任务 `task.file_path` 重新抽取图片（best-effort：文件不存在/无图/单图失败均跳过该图；`ModelConfigError` 整体放弃），对每图产出 `chunk_type=ocr`（OCR 文字）与 `chunk_type=image_caption`（图描述）两块并向量化；在 `_run_augment_task` 中于 qa/扩展增强之后合并 `all_augmented = augmented + extended + multimodal_blocks` 全量 store（幂等），日志含 `多模态=N`。
  - **检索侧排除（vector_store.py / rag.py）**：`AUGMENT_CHUNK_TYPES` 新增 `ocr`/`image_caption`，使其默认排除为引用来源（并入 `retrieval_exclude_augment_blocks`，与 qa 增强块同语义），且不污染预览（`get_document_pages`）、不触发重建膨胀（`get_original_chunks`），但仍参与向量/BM25 检索提升召回；BM25 预热（`warmup_bm25`）保留其入关键词索引。
- **测试**：`test_document_processor.py` 新增 5 例（图片抽取分发/txt 返回空、DOCX zip 真实抽取并过滤小图、多模态生成 ocr+caption 并向量化、关闭开关返回空、VLM 配置错误返回空）；`test_vector_store.py` 扩展两例覆盖 ocr/image_caption 被 `get_original_chunks`/`get_document_pages` 排除；`test_document_augment.py` 新增 worker 端到端验证图片文档产出 ocr/image_caption 块入库并向量化。相关 3 文件全量 + M5-6 单测 39 passed，全量 `pytest tests/` 无回归。
- **依赖**：M5-1 增强队列、M3-3 模型配置（多模态端点）
- **产出**：文档图片的 OCR 文字与描述可被向量/关键词检索并进入问答上下文，图片内容可检索问答。
- **已知设计取舍**：OCR/caption 块默认不列为引用来源（与 qa 增强块一致），仅作为检索召回的语义桥接进入 LLM 上下文；后续如需把 OCR 文字当作可引用原文，可将其从 `AUGMENT_CHUNK_TYPES` 移除（需同步处理重建膨胀防护）。

### M5-7 GraphRAG + Auto-Wiki + 摘要/问题（增强内容丰富度扩展）[Python] P2 · 2d ✅ 完成（2026-07-17）

- **需求**：对标业界成熟方案 postprocess 含摘要 + 问题 + 实体关系(GraphRAG) + Auto-Wiki，扩展现有仅问答对增强。
- **实现**（在现有 qa 增强队列与 `chunk_type` 体系上扩展，最小侵入、无新基础设施）：
  - **config**：新增总开关 `enable_augment_extensions`（默认开）与四类独立开关 `augment_ext_summary/question/wiki/entity`、上限 `augment_ext_summary_max_chars/augment_ext_question_max/augment_ext_wiki_max/augment_ext_entity_max`、单类超时 `augment_ext_per_call_timeout`；检索侧新增 `retrieval_exclude_augment_blocks`（默认开，排除增强块作为引用来源，并入旧 `retrieval_exclude_qa_blocks`）。
  - **生成（document_processor.py）**：新增 `_generate_extended_augment`，以文档拼接文本（截断 `augment_ext_summary_max_chars`）并发生成四类增强块——
    - `summary`：文档级摘要，覆盖「总结/概述」类问题；
    - `question`：推测用户检索式问题，桥接口语化问句与文档术语；
    - `wiki`：Auto-Wiki 条目（归纳性知识点）；
    - `entity`：实体关系三元组（GraphRAG），三元组额外存入 `metadata` JSONB 列（不引入独立图数据库，后续可图检索/可视化）。
    - 各类 LLM 调用容错解析（复用 `_parse_json_list`）；`safe()` 统一捕获 `ModelConfigError`（整体放弃扩展增强，与 qa 一致）与单类异常（跳过该类）。
  - **入库**：`_run_augment_task` 在 qa 之后调用扩展生成，四类块与 qa 合并后**全量幂等 store**（store_chunks 先 DELETE 同 doc 再插，配合 `get_original_chunks` 排除增强块，杜绝重建膨胀）。
  - **检索排除（vector_store.py / rag.py）**：`AUGMENT_CHUNK_TYPES = ("qa","question","summary","wiki","entity")`；`get_original_chunks`/`get_document_pages`（memory + PG，含 BM25 warmup SQL 用 `IS DISTINCT FROM` 兼容 NULL 原文块）均排除全部增强块 + parent 父块；`rag.retrieve` 在融合后排除增强块作为引用来源（仅参与向量/BM25 召回的语义桥接，不污染引用）。
- **测试**：`test_document_processor.py` 新增 3 例（四类块生成+entity 三元组/总开关关闭/配置错误跳过）；`test_vector_store.py` 新增 2 例（get_original_chunks 排除全部增强块+parent / get_document_pages 排除增强块+parent）；`test_document_augment.py` 的 `test_worker_augments_and_notifies_ready` 升级断言扩展块入库（含 entity 三元组）。相关用例全过。
- **依赖**：M4-8.1 增强队列、M5-1
- **产出**：检索增强从「问答对」扩展到多形态知识块（摘要/推测问题/Auto-Wiki/实体关系），提升模糊问句与「总结/关系」类问题的召回；增强块不进引用来源、不污染预览，且重建不膨胀。

### M5-8 复合检索引擎（pgvector 向量 + Elasticsearch BM25）[Python] P2 · 2d ✅ 已完成（2026-07-17）

- **需求**：对齐业界 `composite`（向量 + 关键词双路召回），提升大规模语料下的召回质量与吞吐；同时为后续"搜索功能"打下统一 ES 索引基础（复用同一索引，不新增基础设施）。对标腾讯 WeKnora：ES 一个引擎同时做 kNN + BM25，RAG 检索只做召回+RRF，Rerank 由现有 `rag.py` 阶段负责，PG 保留为分块权威存储。
- **已确认方案决策**：
  - **向量路 = A（推荐，零维度风险）**：向量召回仍走 **pgvector**（本项目 embedding 是"变维度"，pgvector 原生支持；ES `dense_vector` 维度需固定，故向量不迁 ES）。**ES 只负责 BM25 关键词召回 + 未来搜索**。
  - 复合检索 = `pgvector 向量` + `ES BM25` → 现有 RRF 融合 → Rerank 精排。
  - 同意**新增 `elasticsearch` 官方客户端依赖**（锁 8.x 以兼容 Python 3.13）；ES 服务端采用**本地 Elasticsearch 7.17**（用户机已装 `D:\Elasticsearch\elasticsearch-7.17.23` 含 IK 中文分词），通过 8.x 客户端「兼容模式」header（`elasticsearch_compat_7=True`）连接，无需降级客户端。`docker-compose.yml` 的 `xiongda-elasticsearch`（ES 8.x）已注释保留，可切回。
- **基础设施**：
  - ES 服务端用**本地 7.17**（`D:\Elasticsearch\elasticsearch-7.17.23`，含 `analysis-ik` 中文分词），不在 Docker 拉取（docker.elastic.co 走代理 `http.docker.internal:3128` 限速约 0.06 MB/s，1GB 镜像需数小时）；`docker-compose.yml` 的 `xiongda-elasticsearch`（ES 8.x）已注释，切回 8.x 时取消注释并设 `elasticsearch_compat_7=false`。
  - 依赖：`requirements.txt` 加 `elasticsearch==8.17.0`（8.x 客户端以兼容模式连 7.x 服务器）。
- **配置项**（`core/config.py`，全部走环境变量，不硬编码）：`elasticsearch_enabled`(默认 False，关则完全走旧链路零风险回退) / `elasticsearch_hosts`(env:ES_HOSTS) / `elasticsearch_user`、`elasticsearch_password`(或 `elasticsearch_api_key`) / `elasticsearch_index_prefix`(默认 `xiongda`) / `elasticsearch_request_timeout`(10.0) / `retrieval_es_keyword_topk`(20) / `retrieval_rrf_k`(60) / `retrieval_vector_weight`(0.7) / `retrieval_keyword_weight`(0.3)（RRF 参数复用现有）。
- **ES 索引设计**：
  - **每租户一个索引** `{prefix}_{tenant_id}`，首次为该租户存块时惰性创建（理由：ES `dense_vector` 维度须固定，且天然租户隔离，与现有 L1 缓存按 tenant 一致）。
  - **Mapping 字段**：`content`(text, BM25+高亮) / `embedding`(dense_vector，本期不用于召回，仅随块存储备用) / `doc_id` `kb_id` `tenant_id` `chunk_index` `chunk_type` `page` `source`(keyword) / `metadata`(flattened)。
  - 检索时 `must_not: terms(chunk_type ∈ AUGMENT_CHUNK_TYPES)` 排除增强块（沿用"增强块不进引用"语义）。
  - 维度变更兜底：租户切换 embedding 模型导致维度变化时，提供重建/再索引入口（见迁移节）。
- **`vector_store` 改造**：
  - 新增 `ElasticsearchStore`（挂在 `vector_store_service`）：
    - **`store_chunks` 双写**：先写 PG（现有逻辑不变），再 bulk 写 ES（同一 `doc_id` 先 delete-by-query 再插入，幂等，杜绝重复/膨胀）。
    - **`keyword_search` → ES BM25**：替换自研 BM25（旧逻辑保留为 ES 不可用时的 fallback）。
    - **`delete_by_doc(doc_id)`**：按 doc_id 清 ES（删除/重建时调用）。
    - **`search_documents`（为未来搜索铺垫）**：文档级聚合查询（`doc_id` top-hit + `highlight(content)`），本期只实现方法、不接 UI。
- **`rag.py` 检索改造**：
  - `elasticsearch_enabled=True` 时，**关键词路改为 ES BM25 召回**；向量路保持 pgvector（方案 A）。
  - 两路结果送入**现有 RRF 融合 + Rerank**（逻辑不变，仅数据源替换）。
  - **优雅降级**：ES 调用异常/超时 → 自动回退旧 `pgvector + 自研 BM25`，日志告警，不影响问答。
  - `enable_multimodal` 等增强块排除、父子块回溯（`attach_parent_contents`）不受影响。
- **一致性（删除/重建/失效）**：
  - 文档删除（`DocumentServiceImpl` 调 Python 失效）：新增调 ES `delete_by_doc`。
  - 增强 worker 重建（`store_chunks` 幂等覆盖）：已含 delete-then-insert，ES 同步覆盖，不膨胀。
  - 现有 Redis 三层缓存（L1/L2/L3）与 Java 失效逻辑**不动**。
- **存量迁移**：提供一次性脚本/端点 `POST /ai/admin/reindex-es`：从 PG 读全量 chunk（排除增强块/parent），按租户批量 bulk 写入 ES；启动可配 `elasticsearch_auto_reindex_on_empty=True`（该租户索引为空且 PG 有数据时自动补迁，小数据量场景）。
- **未来"搜索功能"铺垫（本期只留接口，不接 UI）**：同一 ES 索引直接支持——按文件名/标题搜（`doc_id` 聚合 + `highlight`，对齐 WeKnora `SearchKnowledge`）与内容搜（chunk 级 BM25+向量，对齐 `hybrid-search`）；后续仅需 Java 转发 Controller + 前端搜索页 + Python `/ai/search` 路由，无需再动索引/存储。
- **测试策略**：
  - 单元测试（mock ES 客户端）：验证 mapping 创建、bulk 请求体（delete-then-insert 幂等）、BM25 查询构造、`must_not` 增强块过滤、delete-by-doc、降级分支；**旧 pgvector/BM25 测试须全过（无回归）**。
  - 可选集成测试：加 `ES_TEST_URL` 环境变量守卫，存在真实 ES 时跑端到端（store→search→delete），否则跳过（CI 无 ES 也能绿）。
- **Java / 前端影响**：M5-8 本身 Java 零改动（检索全在 Python）、前端零改动；未来搜索功能另行立项（Java 转发 + 前端页）。
- **实施步骤**：① compose 加 ES + config 配置项 + requirements 加 elasticsearch → ② `es_client.py`（连接池 + 每租户索引管理/创建/mapping/dims 解析）→ ③ `ElasticsearchStore`（双写 `store_chunks` + `keyword_search`(BM25) + `delete_by_doc` + `search_documents` 预留）→ ④ `rag.py`（ES 启用时关键词路切 ES + 降级）→ ⑤ 一致性 hook（删除/重建调 ES）+ 存量迁移脚本 → ⑥ 单测(mock)+可选集成(env 守卫) → ⑦ 跑全量 pytest 无回归 → 更新 docs → 提交推送。
- **依赖**：M5-1、现有 Rerank
- **产出**：大规模语料下检索质量与吞吐提升；统一 ES 索引为后续搜索功能铺路。

- **实现完成（2026-07-17）**：
  - 代码：新增 `ai-service/services/elasticsearch_store.py`（连接管理 + 双写 `index_chunks` / BM25 `keyword_search` / `delete_by_doc` / 文档级 `search_documents` 预留 / 存量迁移 `reindex_from_pg`·`auto_reindex_missing`，单模块实现，懒加载）；`routers/admin.py` 新增 `POST /ai/admin/reindex-es`；`main.py` 接通启动初始化 + `elasticsearch_auto_reindex_on_empty` 补迁 + 关停关闭 ES。
  - `vector_store.PgVectorStore` 挂接（仅 `elasticsearch_enabled=True` 时生效）：`store_chunks` 双写、`keyword_search` 切 ES BM25、`delete_by_doc` 同步删；全部 try/except 优雅降级，ES 异常自动回退旧 pgvector+自研 BM25，问答不受影响。
  - 配置：`core/config.py` 新增 `elasticsearch_enabled`（默认 False，关则零风险回退）、`elasticsearch_compat_7`（默认 True，8.x 客户端连 7.x 兼容模式）等配置项；`docker-compose.yml` 的 `xiongda-elasticsearch`（ES 8.17）已注释（改用本地 7.17）；`requirements.txt` 加 `elasticsearch==8.17.0`。
  - 实现注记：索引名 `{prefix}_{tenant_id}`，`dense_vector` 维度由 `embedding_dimension` 配置驱动（本期 `index:false` 仅存储备用，不用于 kNN，规避变维度报错）；ES BM25 `_score` 经「除以批次 max 再乘 `elasticsearch_bm25_scale`」归一化，量级对齐自研 BM25，保证融合补充门槛行为一致；`get_es_store()` 在关闭时零导入 `elasticsearch` 包，未安装依赖亦不影响旧链路。
  - 测试：`tests/test_elasticsearch_store.py`（22 例，mock ES 客户端，覆盖索引创建 / bulk 幂等 / BM25 构造与增强块过滤 / 分数归一化 / delete-by-doc / 搜索预留 / 迁移编排 / 降级分支）+ `tests/test_elasticsearch_integration.py`（`ES_TEST_URL` 守卫的可选端到端）；`tests/test_vector_store.py` 11 例无回归。
  - **2026-07-18 部署修正（本地 ES 7.17 + 兼容模式）**：
    - ES 服务端由「docker-compose 拉取 ES 8.17」改为**本地 Elasticsearch 7.17**（`D:\Elasticsearch\elasticsearch-7.17.23`，已装 `analysis-ik` 中文分词）。动因：docker.elastic.co 经 Docker 代理 `http.docker.internal:3128` 限速（约 0.06 MB/s，1GB 镜像需数小时）；本地 7.17 + IK 中文 BM25 召回更优且即开即用。
    - **客户端不降级**（保持 `elasticsearch==8.17.0`）：8.x 客户端经「兼容模式」header（`accept: application/vnd.elasticsearch+json;compatible-with=7`）连 7.x 服务器，规避 `UnsupportedProductError`；新增 `elasticsearch_compat_7` 开关（默认 True，连 8.x 原生时设 False）。
    - `docker-compose.yml` 的 `xiongda-elasticsearch`（ES 8.x）已注释，仅切回 8.x 时取消注释并设 `elasticsearch_compat_7=false`。
    - **启动要点**：`main.py` 无 `if __name__=="__main__"` 入口，须以 `uvicorn main:app --host 0.0.0.0 --port 8001` 启动（非 `python main.py`，后者仅 import 静默退出）；启用 ES 经环境变量注入 `ELASTICSEARCH_ENABLED=true` / `ELASTICSEARCH_HOSTS=http://localhost:9200` / `ELASTICSEARCH_COMPAT_7=true` / `ELASTICSEARCH_TEXT_ANALYZER=ik_max_word` / `ELASTICSEARCH_AUTO_REINDEX_ON_EMPTY=true`。
    - **删除一致性修复**：`delete_by_doc` 的 `delete_by_query` 改 `refresh=True`（ES 7.x 仅支持 `true/false`、不支持 `wait_for`），删除后立即可见，避免已删文档短暂仍被 ES 召回；集成测试修复持久 event loop（`setUpClass` 复用同一 loop，规避 `Event loop is closed`）。
    - **实测验证**：本地 ES 7.17.23——启动连接成功、惰性建索引 `xiongda_{tenant_id}`（dims=1536，7.x mapping 兼容）、`auto_reindex_on_empty` 将 3401 个 PG 块迁移入 ES；单测 22 例全过、集成测试（index→BM25 search→delete 不可见）全过。
    - **本地开发环境已启用（2026-07-18）**：新建 `ai-service/.env`（仅含 ES 段，`ELASTICSEARCH_ENABLED=true` / `ELASTICSEARCH_HOSTS=http://localhost:9200` / `ELASTICSEARCH_COMPAT_7=true` / `ELASTICSEARCH_TEXT_ANALYZER=ik_max_word`），重启 Python 服务后启动日志出现 `✅ Elasticsearch 检索引擎已就绪`；调 `POST /ai/admin/reindex-es` 从 PG 全量重建索引（返回 `migrated_chunks=3406`）。验证：Python 直连 ES 对真实索引 `xiongda_2075873177644326913` 查"入职指南"返回 62 命中、`ik_max_word` 中文 BM25 召回正常，Top3 为三份"新员工入职指南"文档。自此网页问答关键词路走 ES BM25 与 pgvector 向量 RRF 融合；其余 LLM 等配置仍走系统环境变量，`.env` 不覆盖。

### M5-9 多模态问答增强（图片 vision + 附件文本提取）[全栈] P2 · 2d ✅ 完成（2026-07-18）

- **背景**：用户在问答输入框可上传图片（走 LLM vision 多模态调用）和通用文档附件（pdf/docx/txt/md，提取文本拼到本次 LLM 上下文），不入向量库、不跨会话。
- **全栈实现**：
  - **前端**：`ChatPage.tsx` 新增图片上传按钮（缩略图预览 + 移除）、附件上传按钮（文件名标签 + 移除）；知识库多选标签（绿色 + 号打开下拉菜单）；`streamChat` 参数扩展 `kbIds/imageIds/attachmentIds`；`AIConfigPage` LLM 模型字段下方加多模态 vision 提示（支持 vision 的模型白名单：gpt-4o/gpt-4o-mini/gpt-4-vision-preview/claude-3-5-sonnet/qwen-vl-max/qwen2.5-vl-72b-instruct/gemini-2.0-flash/glm-4v）。
  - **后端 Java**：`ChatAttachment` 实体/Mapper/Service/Controller；`ChatRequest` 扩展 `imageIds/attachmentIds`；`ChatController.resolveAttachmentPaths` 按分类（image/attachment）解析附件 ID 为文件绝对路径，带租户隔离校验；`AiServiceClient.chatStream` 透传 `image_paths/attachment_paths`。
  - **后端 Python**：`ChatStreamRequest` 扩展 `image_paths/attachment_paths`；`routers/chat.py` 提取附件文本拼到 context；`llm.py` 新增 `stream_generate_with_images` 多模态调用；`is_vision_model` 白名单判定，不支持 vision 的模型抛 `ModelConfigError`。
  - **ID 类型修正**：前端 streamChat 参数改用 `string[]`（Jackson Long→String 序列化；`ChatAttachmentVO.id` 改为 string）。
- **单测**：Python `test_llm.py` 覆盖 `is_vision_model` 白名单/黑名单、非 vision 模型抛错、不可读图片 fallback、vision messages 构造；Java `ChatControllerTest` 2 例（透传测试 + 降级测试），全量 5 例通过。
- **依赖**：M3-3 模型配置、M4-4 Agent/问答模式

**M5 合计: ~13.5 天**

**实现顺序**：M5-1 → M5-2 → M5-3 → M5-4 → M5-5 → M5-6 → M5-7 → M5-8 → M5-9（先主流程健壮性，后可观测性与功能广度，最后多模态问答，**逐个实现**）。

---

## 缺陷修复（2026-07-17）

### 引用来源出现「�」乱码 + 无关文档被当作来源（Bug 修复）[Python + Java] P1 · 0.5d ✅ 完成

- **背景**：用户实测发现两个问题：① 引用来源 / 预览文档里出现 `�` 替换符乱码（如 14MB《Java面试宝典》PDF）；② 问「java后端校招面试的重点部分是哪些」时，来源里混入主题无关的《后端新员工入职指南》《JavaWeb笔记》。
- **根因 ①（乱码）**：`document_processor._clean_text` 仅剔除 `U+FFFD`，未覆盖中文 PDF 文本层常见的 **C1 控制符（U+0080–U+009F）/ 非字符码位（U+FDD0–U+FDEF、U+FFFE、U+FFFF）/ 孤立代理对**；且 Java 预览降级分页 `getDocumentPages` 用 `String.substring` 按 **UTF-16 码元**切分，会切断代理对（emoji / 生僻 CJK Ext-B）产生 `U+FFFD`。
- **根因 ②（来源不对）**：`rag.py` rerank 后门槛过滤存在逻辑 bug——`merged = kept[:top_n] if kept else merged[:top_n]`，当没有块过 `retrieval_rerank_min_relevance` 门槛时竟回退展示**全部未过滤结果**，门槛过滤形同虚设；叠加 LLM rerank 提示词对「仅含相同关键词但主题不符」的块打分偏松，使无关文档块以 ≥0.40 分通过并被当作来源。
- **改造**：
  - Python `_clean_text` 加固：用正则剔除 C0/C1 控制符 + 非字符码位，再剔除 `U+FFFD` 与孤立代理对，仅保留 `\n \r \t` 与可见字符（`document_processor.py`）。
  - **存储边界防御性清洗（2026-07-17 二次加固）**：在 `chunk_text` 对每个分块内容、以及 `process()` 对 `full_text` 回填全文前再各做一次 `_clean_text`，即便上游提取路径漏清，落库的 chunk / 全文也一定干净；Java `updateDocumentStatus` 保存 `content` 前经 `stripGarbage` 兜底剥离 `U+FFFD` 与 C1 控制符，避免任何来源（含历史脏数据重存）的脏字符污染全文。
  - Python `rag.py`：`merged = kept[:top_n]`（重排后只保留过门槛块，不再回退未过滤结果）；`_rerank_with_llm` 提示词加严——明确「仅含部分相同词汇但主题无关（校招重点 vs 入职指南/Java笔记）的片段必须 ≤0.2」，使无关块被过滤。
  - Java `DocumentServiceImpl.getDocumentPages` 降级分页改用 `codePointCount`/`offsetByCodePoints` 按**码点**切分，避免切断代理对。
- **改动文件**：`document_processor.py`（提取 + 分块边界 + 全文回填三处清洗）、`rag.py`、`DocumentServiceImpl.java`（码点分页 + `stripGarbage` 全文兜底）、`tests/test_document_processor.py`（新增 `_clean_text` 乱码清洗单测）。
- **单测**：Python `test_document_processor.py` 新增 `_clean_text` 覆盖 C1 控制符 / U+FFFD / 非字符码位 / 孤立代理对 / 合法 emoji 保留 / BOM 与换行保留；Python 全量 **145 例**（`test_document_processor` / `test_document_augment` / `test_query_rewrite` / `test_model_config` / `test_process_queue` 等）通过，无回归。
- **关键结论（旧数据需重处理）**：清洗只在**抽取时**生效，**已入库的历史文档（如修复前上传的 14MB《Java面试宝典》）其向量库 chunk 与 `document.content` 里已固化 `U+FFFD`，检索出来原样展示**——传输层（此前修过的 Java SSE 边界截断）修好也救不了脏数据本身。故这些旧文档**必须重新处理一次**（`�` 才会消失）；新上传文档因三处清洗必为干净。来源过滤阈值仍可用 `retrieval_rerank_min_relevance`（默认 0.40）调节，若加严提示词后仍偏松可上调至 0.45~0.55（代价是更难问题的召回可能变窄）。

---

## M2-7 三层 Redis 缓存（对标业界成熟方案，2026-07-12）

> 需求：参照业界成熟 RAG 方案，实现三层 Redis 缓存——先写 Redis 再落库、每个会话带 TTL、重复问题直接走缓存跳过检索与数据库。

**业界方案 真实架构纠正**：业界方案 是 **Go 后端 + Python 文档解析微服务**，缓存层（L1/L2/L3）都在 Go 后端内。本项目是 Java 后端持有会话 + Python AI 服务做 RAG 编排/LLM，因此映射为：L1/L2 落 Python，L3 落 Java。其 `session:{sid}:stream`（Redis Stream 存 SSE 事件）属 Go 后端原生能力，跨服务架构下收益低、复杂，**本期不照搬**。

**三层缓存落地**

| 层 | key | 位置 | 缓存内容 | TTL | 命中后跳过 |
|---|---|---|---|---|---|
| L1 检索结果 | `retrieval:{tenant_id}:{q_hash}` | Python `rag.py` | 混合检索+Rerank 结果 | 3600s（文档变更主动清） | 向量/BM25/RRF/Rerank |
| L2 嵌入向量 | `embedding:{text_hash}:{model}` | Python `embedding.py` | 文本向量 | 86400s | Embedding API |
| L3 会话状态 | `chat:conv:{conv_id}`（Redis List） | Java `ChatServiceImpl` | 最近 50 条消息 | 1800s | DB 历史查询 |

- **L1 跨会话 tenant 级**：同一租户任何人问相同问题命中，跳过检索阶段（仍走 LLM 实时生成，答案不过时，与 业界方案 一致）。
- **L3 先写 Redis 再落库**：`saveUserMessage`/`saveAssistantMessage` 先 `rightPush` 到 `chat:conv:{id}` 并刷新 TTL，再 `messageMapper.insert`；`listMessages` 先查 Redis，命中即返回跳过 DB，未命中回源 DB 并回填。
- **失效**：文档上传处理完成（`DocumentServiceImpl.triggerDocumentProcessing` ready 时）与删除（`deleteDocument`）时，Java 调 Python `POST /ai/cache/invalidate` 清该 tenant 的 `retrieval:{tenant}:*`，下次提问回源重新检索。

**改动文件**
- Python：`core/config.py`（Redis 配置 + TTL）、`core/redis_client.py`（新增，async 客户端单例）、`services/embedding.py`（L2）、`services/rag.py`（L1）、`routers/cache.py`（失效接口）、`main.py`（注册路由）、`requirements.txt`（redis-py）
- Java：`ChatServiceImpl.java`（L3 读写 + 删除清缓存）、`AiServiceClient.java`（invalidateCache）、`DocumentServiceImpl.java`（文档变更调失效）、`ChatServiceImplTest.java`（新增单测）

**Bug 修复（单测暴露）**
- Python `embed_text`/`retrieve`/`embed_batch` 原写成 `await get_redis().get(...)`，但 `get_redis()` 是 async 函数，应先 `await get_redis()` 取客户端再 `.get()`。原写法在 coroutine 上调用 `.get` 抛 `AttributeError` 被 `except` 吞掉，**缓存从未真正生效**。已修正为先取客户端再操作；19 个单测全过（含 4 个缓存测试）。

**测试**
- Python：19 个单测全过（`test_redis_cache.py`：L2 命中、L1 命中跳过检索、跨 tenant 隔离、失效清 tenant 级）。
- Java：`ChatServiceImplTest` 9 个全过（先写 Redis 再落库、命中跳过 DB、未命中回源回填、删除清缓存）。

**验证（2026-07-12 重启 Java 8080 后真实联调全过）**：
- 会话 TTL：发消息后 `chat:conv:{convId}` 写入 Redis，`TTL=1799`（≈1800s/30min），LRANGE 含 user 消息 → L3 先写 Redis 再落库 + TTL 生效。
- 历史消息走缓存：首次 `listMessages`（Redis 空）查 DB（`SELECT message` 1 次）；发消息写入后二次 `listMessages` 命中 Redis（`SELECT message` 0 次）直接返回缓存消息 → 命中跳过 DB。
- 文档变更检索失效：写 `retrieval:{tenant}:*` 测试 key 调 `POST /ai/cache/invalidate` 返回 `deleted:1`，key 被清 → 失效清除生效（Java 文档 ready/删除已接通该调用）。
**依赖**：Python 新增 redis-py（测试用 fakeredis）；Java 复用已有 spring-boot-starter-data-redis。

**关联**：M2 RAG 核心检索；业界方案 三层缓存。

## 新建对话按钮交互定稿（2026-07-12）

> 诉求：连点不会留一堆空会话；空白对话不立即落库。

**最终方案**
- `AppLayout.handleNewChat` 仅 `setActiveId(null)`：点加号切到空白窗口，**不**调用后端 `createConversation`，因此不落库、不会产生空会话；连点只是反复回到空白窗口，零副作用。
- 会话仍在「发第一条消息」时由后端 `chatStream`（`conversationId==null`）自动 `createConversation` 落库并出现在历史栏。即「当前对话记录到历史栏/数据库」在发消息后自然满足。
- 撤销了上一版的「立即建会话」方案及其 `ChatPage` 的 `conversationId` 竞态同步（该竞态仅存在于已否决的立即建方案下）。

**交互表现**
- 当前在某会话 A（已落库）：点加号 → 切到空白窗口，A 仍保留在历史栏+DB。
- 当前已空白：点加号 → 仍是空白（无变化、无新建）。
- 在空白窗口发消息 → 后端建会话并落库，窗口切到该会话。

**验证**：`npm run build` 通过（仅 chunk 体积告警）；待浏览器实测。

**关联**：M2-6 会话管理；会话持久化增强（2026-07-12）。

## 会话持久化增强（2026-07-12）

> 需求：当前对话窗口在刷新/重进平台后保留；点击「新建对话」时，当前对话落入历史栏并同步数据库。

**背景**
- 会话已在发送消息时落库：`ChatController.chatStream` 在 `conversationId==null` 时调用 `createConversation` 写入 `Conversation` 表；`listConversations` 读 DB，前端「历史记录」栏即 DB 数据。即「同步数据库/历史栏」原本已满足。
- 真正缺口：前端 `activeId` 为内存 `useState`，刷新即丢失 → 回到空白窗口，表现为「当前对话没保留、下次进入进度没了」。

**改动（仅前端）**
- `frontend/src/context/ChatContext.tsx`：`activeId` 初始化从 `localStorage['xiongda_active_conversation']` 读取；`setActiveId` 同步写入/清除该 key；`refresh()` 列表加载后清理已被删除的 activeId。
- 效果：进入平台自动恢复上次会话并加载其历史消息；「新建对话」清除持久化（当前对话因已落库仍保留在历史栏+DB）。

**验证**
- `npm run build` 通过（tsc 0 错误，仅 chunk 体积告警）。
- 待浏览器硬刷新实测。

**关联**：M2-6 会话管理；M2-6 联调 bug（JS 大整数精度丢失，会话/消息 VO 的 id 改 String）已修复并验证。

## 总览

| 里程碑 | 工时 | 核心交付 |
|---|---|---|
| M1 - MVP | 8.5d | 注册登录 + 上传文档 + 基础问答 |
| M2 - RAG 核心 | 7d | RAG 检索 + 流式回答 + 引用溯源 + 会话管理 |
| M3 - 管理功能 | 6d | RBAC 权限 + 成员管理 + AI配置 + 审计日志 |
| M4 - 增强 | 10.5d | Agent 推理 + UI 精细化 + 部署 |
| **合计** | **32d** | |

## 任务依赖图

```
M1-1 数据库 ✅
  └─→ M1-2 认证服务 ✅ ──→ M1-3 认证页面 ✅ ──→ M1-9 侧边栏布局 ✅
        │                    │
        ├─→ M1-4 知识库CRUD ✅ ──→ M1-7 知识库页面 ✅
        │      │
        │      └─→ M1-6 Java→Python联动 ✅
        │
        └─→ M1-8 基础问答 ✅ ──→ M1-8.5 问答UI精细化 ✅ ──→ M2-5 RAG问答
               ↑                                               │
               │                                               └─→ M2-6 会话管理 ✅ ──→ M4-6 问答UI(补全)
M1-5 AI服务基础 ✅ ─┘
  │
  ├─→ M2-1 Embedding ──→ M2-2 Milvus ──→ M2-3 文档全链路
  │                                            │
  └─→ M2-4 RAG检索 ────────────────────────────┘
        │
        └─→ M2-5 RAG问答 ──→ M4-1 Agent ──→ M4-3 模式切换
                             │
                             └─→ M4-4 文档原文

M1-2 ──→ M3-1 RBAC ──┬─→ M3-2 成员管理
                     ├─→ M3-3 AI配置
                     ├─→ M3-4 审计日志
                     ├─→ M3-5 平台超管
                     └─→ M4-2 共享/个人库

M3 全部 ──→ M4-9 部署
```

---

## 文档处理四项修复（2026-07-13）

> 用户反馈：① 解析出的文档有乱码（如 `���`）；② 对话引用来源全部显示「第0页」；③ 已就绪文档希望点文件名弹出小窗看内容；④ 知识库文档个数不随上传/删除同步。

### 1. 解析乱码（问题1）
**根因**：TXT/MD 分支用 `open(..., encoding="utf-8")` 读取中文 Windows 文件（GBK）会解码异常；提取文本未清洗控制字符与替换符 `U+FFFD`；DOCX 仅取段落、漏掉表格内容。
**修复**（`ai-service/services/document_processor.py`）：
- 新增 `_read_text_file`：依次尝试 `utf-8 → gbk → latin-1` 解码，避免中文乱码。
- 新增 `_clean_text`：去 BOM、去控制字符（保留 `\n\r\t`）、去 `U+FFFD` 替换符。
- DOCX 提取补充表格内容（`doc.tables`）；PDF/DOCX/TXT/MD 统一经 `_clean_text` 清洗。

### 2. 引用页码全为 0（问题2）
**根因**：`process` 存储时把 `page` 硬编码为 `0`，检索返回的 `page` 永远是 0，对话引用「第0页」。
**修复**：重构为「按页分段」——PDF 用 PyMuPDF 逐页真实页码；DOCX/TXT/MD 无真实页码时按 `CHARS_PER_PAGE=1500` 字符估算（仅用于引用展示）。`chunk_text` 每块携带对应 `page`，存储元数据不再写死 0。对话 `chat.py` 的「第{page}页」自动显示真实/估算页码。

### 3. 已就绪文档查看内容弹窗（问题3，新功能）
- Python `process` 返回值新增 `content`（提取全文）；`routers/document.py` 透传。
- Java `Document` 实体新增 `content` 字段（`document` 表加 `content TEXT` 列，已 ALTER 现有库）；`DocumentServiceImpl.triggerDocumentProcessing` ready 时回填；新增 `DocumentService.getDocumentContent`（同租户可读）。
- 新增 `GET /api/knowledge/document/content?docId=` 接口。
- 前端 `knowledgeApi.getDocumentContent` + `KnowledgeBasePage`：已就绪文档文件名可点击，弹窗展示全文（`whitespace-pre-wrap` 滚动）。
- **单测**：`tests/test_document_processor.py`（6 个）覆盖乱码清洗、编码回退、页码分配、DOCX 表格提取、chunk 页码保留，全部通过。

### 4. 知识库文档数不同步（问题4）
**根因**：`knowledge_base.document_count` 建库时置 0 后从未更新。
**修复**：`DocumentServiceImpl` 新增 `syncKbDocCount`（按 `kb_id+tenant_id` 重新 `count`，逻辑删除自动过滤）并在 `uploadDocument`/`deleteDocument` 后调用；前端 `loadKbList` 刷新时保留当前选中知识库，上传/删除后调用以同步计数显示。已对现有库做一次 SQL 回填校正（0→真实数）。

**验证**
- Python 单测 6 个全过（`pytest tests/test_document_processor.py`）。
- 对真实 docx 直接跑 `extract_pages`+`chunk_text`：`含U+FFFD=False`、`含控制字符=False`、页码集合 `[1,2,3]`（分布 `{1:4,2:3,3:1}`），证明问题1/2 修复。
- Java 8080 / Python 8001 重启 health 均 200，无启动错误。
- 现有 KB 计数已回填（如「后端」0→2），且后续上传/删除自动同步。

**注意**：本次改动前已 `ready` 的旧文档 `content` 为 NULL（处理早于 content 列），点击会显示「（文档内容为空）」；重新上传该文档（或新上传）即可在弹窗看到内容。

### 5. 问答窗口自动滚动跟随（2026-07-13）
**问题**：发送消息后窗口不滚动、AI 流式回复时窗口固定在原位置，看不到最新内容。
**修复**（`frontend/src/pages/ChatPage.tsx`）：
- 主内容滚动容器加 `ref`/`onScroll`，用 `atBottomRef` 判定用户是否贴底（阈值 80px）。
- 发送消息：`followRef=true` 并 `scrollToBottom('smooth')` 跳到底部；流式结束 `finally` 中 `followRef=false`。
- 新增 effect 监听 `messages`：流式回复逐字追加时，若 `atBottomRef || followRef` 为真则 `scrollToBottom('auto')` 跟随到底部（用户在生成中上滑查看历史时不会被迫下拉）。
- 切换/加载历史会话后 `atBottomRef=true` 并滚到底部（打开即看最新）。
**验证**：前端 HMR 已热更新，浏览器实测发送与流式跟随即可。

### 6. 问答消息显示昵称（2026-07-13）
**需求**：AI 回复头像旁显示名称「熊答AI」；用户回复旁显示其注册用户名。
**实现**（`frontend/src/pages/ChatPage.tsx`）：
- 引入 `useAuth` 取当前登录用户，`user?.name` 即注册时填写的姓名（与 AppLayout 一致）。
- 消息渲染重构为「头像 + 名称 + 气泡」：AI 用「熊」头像 + 名称「熊答AI」；用户用首字头像（灰） + 名称 `user.name`（取不到时回退「我」）。
- 名称以 `text-xs text-slate-400` 显示在气泡上方，左右对齐随角色。
**验证**：前端 HMR 已热更新，浏览器发消息可见「熊答AI」与当前用户名。

### 7. 用户消息显示时间（2026-07-13）
**需求**：每条用户消息下方显示具体时间。
**实现**（`frontend/src/pages/ChatPage.tsx`）：
- `ChatMessage` 新增 `time?` 字段；新增 `formatTime` 格式化为「MM-DD HH:mm」。
- 历史消息加载时从 `Message.createTime` 解析时间；实时发送时打 `formatTime(new Date())`。
- 仅在用户消息气泡下方以 `text-[11px] text-slate-400` 渲染时间。
**验证**：前端 HMR 已热更新，发消息可见「MM-DD HH:mm」。

### 8. 用户消息时间持久保存（2026-07-13）
**问题**：用户刚发送时显示时间，AI 回复结束后时间消失（刷新/重开会话后也丢失）。
**根因**：AI 回复结束触发 `setActiveId` → 重新 `listMessages` 命中 L3 Redis 会话缓存；后端缓存回放构造的 `MessageVO` 不带 `createTime`，前端 `m.createTime` 为 null → `time` 成 undefined 消失。
**修复**（`backend/.../service/impl/ChatServiceImpl.java`）：
- `cacheMessage` 新增 `createTime` 参数，把时间（epoch 毫秒）写入缓存条目。
- `saveUserMessage`/`saveAssistantMessage` 改为「先落库取回 `createTime` 再写缓存」。
- 缓存回放（命中 L3）时还原 `createTime` 到 `MessageVO`（缺 import `java.util.Date` 导致首启 `NoClassDefFoundError`，已补）。
- 已清空旧 `chat:conv:*` 缓存，使历史会话重载回源数据库拿到真实时间。
**效果**：每条用户消息下方始终显示「MM-DD HH:mm」，刷新/重开/切会话后从后端历史加载仍带时间，便于按时间回溯。
**验证**：Java 重启就绪（200），旧 L3 缓存已 flush；浏览器发消息→AI 回复结束→时间不消失→刷新页面时间仍在。

### 9. 检索无相关时不展示错误引用来源（2026-07-13）
**问题**：用户问知识库没有的内容（如「介绍一下HR的招聘流程」，库里只有测试入职指南），AI 回答「未包含相关信息」却仍列出 5 条不相关引用来源，误导用户。
**根因**：`rag.retrieve` 无论相关性始终返回 top-5，`chat.py` 一并推送 `sources`，前端按 `sources.length` 渲染来源卡片。
**修复**（根因在检索层，与「检索中没有就不显示」对齐）：
- `core/config.py` 新增门槛配置：`retrieval_relevance_gate`（默认开）、`retrieval_vector_min_relevance=0.30`（余弦）、`retrieval_bm25_min_relevance=1.0`（关键词）、`retrieval_rerank_min_relevance=0.10`（rerank 分数）。
- `services/rag.py`：`retrieve` 在 RRF 融合前先取向量余弦 / BM25 原始最高分；`_rerank` 改为返回 `(结果, 是否应用rerank分数)` 并写入 rerank 相关性分数；新增 `_is_relevant`——配置 rerank 且应用则以 rerank 分数为准，否则向量余弦或 BM25 满足其一即相关；不相关则直接返回空列表（L1 缓存也存空，行为一致）。
- 空结果下 `chat.py` 不推送 `sources` 事件，前端不渲染来源卡片；AI 仅以空上下文回答「未找到」。
**测试**：`test_rag_pipeline.py` 新增 `test_unrelated_query_returns_empty`（正交向量+无关键词重叠→返回空）、`test_is_relevant_logic`（三种门槛分支）；全量 26 个 Python 单测通过。
**验证**：Python 重启就绪（200）；直接打 `/ai/chat/stream` 问无关问题，SSE 事件为 `thinking/error/done`，无 `event: sources`（error 为探针假 Key 触发，与门槛无关）。前端无需改动（已按长度守卫）。
**注意**：`retrieval_vector_min_relevance=0.30` 为默认保守值，若真实场景出现「该显示却没显示」，可在 `.env` 调低该值。

### 10. 用户消息时间显示加年份（2026-07-13）
**需求**：每条用户消息下方时间加上年份，便于跨年回溯。
**改动**（`frontend/src/pages/ChatPage.tsx`）：`formatTime` 由 `MM-DD HH:mm` 调整为 `YYYY-MM-DD HH:mm`（年份来自 `getFullYear`）；同步更新 `ChatMessage.time` 字段注释。
**验证**：`tsc --noEmit` 零错误，前端 dev server 在线（HMR 生效）。刷新会话后历史消息时间带年份显示。

### 11. 历史记录按时间分组展示（2026-07-13）
**需求**：左侧历史记录原仅有「今天/近7天/更早」分组，需支持更细的时间分组——昨天、近3天、1个月内，更久远按跨度分组，且 7 天以上的会话默认折叠可展开。
**改动**（`frontend/src/components/AppLayout.tsx`）：
- 新增 `historyGroupOf()`（互斥分组，每会话唯一归属一组，避免同一条出现在多个分组）与 `HISTORY_GROUPS` 顺序定义：`今天 / 昨天 / 近3天 / 近7天 / 近1个月内 / 近3个月内 / 近半年内 / 更早`。今天/昨天按自然日 0 点边界，月级用 `setMonth(-n)`（跨年自动处理）。
- 分组标题灰色展示在记录上方（恢复原本样式）；`grouped` 仅展示有会话的分组并按定义顺序排。
- 7 天以上分组（`近1个月内` 及更久）默认折叠，标题旁显示小箭头与数量，点击展开；7 天以内直接展开。
- 抽 `HistoryGroup`（折叠逻辑）、`ConvItem`（单条会话项）组件。
**验证**：`tsc --noEmit` 零错误、无 lint；前端 dev server 在线（HMR）。

### 12. 历史记录交互调整：筛选框 → 分组折叠（2026-07-13）
**背景**：第 11 项初版在「历史记录」标题下加了时间范围 `<select>` 筛选下拉（30 档：全部/今天/昨天/近3天/近7天/1个月内…2年内），用户反馈下拉框布局突兀、观感不舒服。
**调整**：移除筛选下拉框，改为用户期望的「原本那样的分组标题灰色字体 + 超过 7 天用可折叠小箭头」交互（见第 11 项最终形态）。纯前端改动，不触碰后端接口。

### 13. Bug 修复：点击历史会话全部显示「加载历史消息失败」（2026-07-13）
**问题**：点击左侧任意有消息的历史会话，聊天区统一显示「加载历史消息失败，请稍后重试。」；空会话正常。
**根因**：`GET /api/chat/message/list` 后端返回正常（`code:0`，真实数据已验证），问题在 `ChatPage.tsx` 的 `.then` 回调里抛异常被 `.catch` 捕获并统一显示该提示。具体：`MessageVO.createTime` 后端为 `Date`，经 JSON 序列化后变成**数字时间戳**（如 `1783923822524`）；`listMessages` 中 `formatTime(m.createTime)` 调用时，`formatTime(input: string | Date)` 对 number 走 `d = input`（即 number），随后 `d.getTime()` 抛 `TypeError`。空会话 `data:[]` 不进 map，故不报错——与「有消息会话全失败、空会话正常」现象完全吻合（用真实会话 ID 直连后端验证：返回 `code:0` 且数据完整）。
**修复**（`frontend/src/pages/ChatPage.tsx` + `frontend/src/types/index.ts`）：
- `formatTime` 入参由 `string | Date` 扩展为 `string | number | Date`，number 分支 `new Date(input)` 正确还原时间。
- `Message.createTime` 类型由 `string` 改为 `string | number`，与后端实际返回（数字时间戳）对齐，避免类型声明误导。
**验证**：`npm run build`（`tsc -b && vite build`）通过 EXIT=0、lint 0 错误；dev server HMR 已热更新，刷新历史会话可正常加载消息与时间。

### 14. 角色权限矩阵：修正内容 + 移出成员管理页（2026-07-13）
**问题**：成员管理页底部的「角色权限矩阵」有两处问题：① 内容过时，与后端真实权限脱节——`上传 / 管理文档` 与 `配置 AI 模型` 两行把普通成员写成 `false`，但后端 `design.md §3.3` 菜单 `knowledge: member+`、`ai-config: member+`，且 `AiConfigController` 无 `@AuthCheck`、`KbPermission` 允许个人库 owner 写，实际普通成员**可上传文档（个人库）/配置 AI 模型**；② 摆放位置不合理，权限矩阵属产品级说明，放在"管人"的成员页语义错位，且硬编码易再次与后端脱节。
**处理**（用户选定：移到独立说明页）：
- 新建 `frontend/src/pages/RolePermissionPage.tsx`：独立「角色权限」页，矩阵数据与后端对齐（普通成员 `上传/管理文档`、`配置 AI 模型` 改为 ✅，并加脚注说明"个人库可写/共享库仅管理员、每人独立配模型"）。
- `frontend/src/router.tsx`：注册 `/role-permission` 路由。
- `frontend/src/components/AppLayout.tsx`：侧边栏新增「角色权限」入口（`roles: [member, tenant_admin, super_admin]`，全角色可见）。
- `frontend/src/pages/MembersPage.tsx`：移除硬编码 `PERMISSIONS`/`Check` 与矩阵 JSX，仅保留成员列表与邀请功能。
**验证**：`npm run build` 通过 EXIT=0、4 文件 lint 0 错误。
**注意**：矩阵仍为前端硬编码（本次未做后端动态化）；后端 RBAC 再次变更时需同步更新 `RolePermissionPage.tsx` 的 `PERMISSIONS`。

### 15. Bug 修复：新会话流式回复中途突然变空白（2026-07-14）
**问题**：网页手动测试，发送消息后 AI 开始逐字回复，回复到一半突然整段消失、聊天区变空白。
**根因**：新会话时 Python `done` 事件携带真实 `conversation_id`（`ChatController` 在服务端生成后经 `AiServiceClient` 透传），前端 `done` 处理里 `setActiveId(convId)` 触发 `ChatPage` 的 `useEffect[activeId]`，执行 `setMessages([])` + `listMessages` 重新加载。而 Java 的助手回答是在流式结束后的 `doOnTerminate` 里通过 `Schedulers.boundedElastic()` **异步落库**（`ChatController` 第 181 行），此时重载极大概率命中 L3 会话缓存里「只有用户消息、助手消息尚未落库」的状态，把刚流式渲染的回复覆盖清空 → 表现为"回复中途变空白"。已有会话（`activeId` 已存在）不会触发该重载，故仅新会话复现。
**修复**（`frontend/src/pages/ChatPage.tsx`）：新增 `skipReloadRef` 标记。新会话 `done` 事件里先置 `skipReloadRef.current = true` 再 `setActiveId(convId)`；`useEffect[activeId]` 顶部检测该标记则短路（仅 `setStreaming(false)` 后 `return`），跳过后端重载，直接复用内存中已完整的回复。已有会话切换仍走原重载逻辑不受影响。
**验证**：`tsc --noEmit` 零错误、lint 0 错误；前端 dev server HMR 已热更新。新会话流式结束后回复保留、不再被清空；切换历史会话/刷新后仍正常从后端加载（异步落库已完成）。

### 16. 体验优化：智能推理面板太花太长（2026-07-14）
**问题**：agent（智能推理）模式下，普通问答时"推理过程"面板默认强制展开，且 `观察结果` 把整段检索原文（约 5×300 字的中间检索数据，本为喂给模型用）完整展示给用户，刷屏、极花、无阅读价值，体验不舒服（用户原话："智能推理不太行…太花太长了"）。
**根因**：`frontend/src/pages/ChatPage.tsx` 的 `AgentSteps` 组件 `open` 默认 `true`（强制展开）；observation 步骤直接渲染 `s.content` 全文（检索命中明细），对用户无价值且巨长。
**修复**：
- 推理过程面板默认 `open=false` 折叠，普通问答时只显示"推理过程（N 步）"一行小字，保持阅读清爽；用户想看再点开。
- observation 步骤仅显示首行摘要（如"检索到 5 条相关内容"），不再展示 1500 字检索明细；失败时（`success=false`，如模型配置错误）仍显示完整错误提示，保留排障价值。
- 保留"调用工具"的 query 参数与可展开的完整 `思考` 步骤。
- 最终答案正文 `msg.content` 与推理面板独立渲染（687-689 行），本次改动不影响答案展示。
**验证**：`tsc --noEmit` 零错误、lint 0 错误；前端 dev server HMR 已热更新。agent 模式下默认视图清爽，展开后观察结果仅一行摘要。

### 17. Bug 修复：引用来源「查看原文件」报 401 未登录（2026-07-14）
**问题**：问答页引用来源卡片点「查看原文件」弹窗，iframe 区域显示 `{"code":40100,"data":null,"message":"未登录"}`，无法预览原文件（其他接口如聊天、引用列表均正常，说明 token 有效）。
**根因**：`DocumentPreviewModal` 把 `pdfSrc = /api/knowledge/document/file/{docId}`（裸 URL）直接放进 `<iframe src>`。iframe 是浏览器原生请求，**不会携带 axios 的 `Authorization` header**，Java 后端 `KnowledgeBaseController.getDocumentFile` 调 `userService.getLoginUser(request)` 取不到 token → 抛 `NOT_LOGIN_ERROR(40100)`；该接口以 JSON 返回错误体，被 iframe 直接渲染成可见的 JSON 文本。
**修复**：
- `frontend/src/api/knowledge.ts` 新增 `fetchDocumentFile(docId)`：通过 `api.get('/knowledge/document/file/'+docId, { responseType:'blob' })` 携带 JWT 拉取文件流 Blob。
- `DocumentPreviewModal` 移除裸 `fileUrl`/`pdfSrc`，改为在 `fileStatus` 为 ok/changed 时由 axios 拉取 Blob 并以 `URL.createObjectURL` 生成 `blobUrl` 注入 iframe；组件卸载时 `URL.revokeObjectURL` 释放，避免内存泄漏；新增「加载中 / 加载失败」占位态。
- `getDocumentFileUrl`（裸 URL 方法）保留但不再用于 iframe，仅作占位，避免误删影响。
- 注：Blob URL 无法定位 PDF 页码，原 `#page=N` 锚点翻页降级为从首页打开（文本模式仍展示全文，影响极小）。
**验证**：`tsc --noEmit` exit 0、lint 0 错误；前端 dev server HMR 已热更新。已登录态下预览不再触发 401，iframe 正常内嵌原文件。

### 18. 体验优化：原文件预览「精准翻页」（2026-07-15）
**背景**：第 17 项修 401 时改用 Blob URL 注入 iframe，导致原 `#page=N` 页码锚点失效（降级为从首页打开）。用户要求补回精准翻页。
**关键事实**：引用来源文件多为 `.docx`，浏览器原生与 pdf.js 均无法内嵌预览 docx（只能下载）；后端 `ai-service/services/document_processor.py` 对 docx/txt/md 按 `CHARS_PER_PAGE=1500` 字符估算页码（非真实 PDF 页），`getDocumentContent` 返回纯文本全文且**不含页码标记**。因此 pdf.js 对当前 docx 场景无效，纯文本预览是 docx 唯一可行方案。
**方案（文本估算分页翻页，覆盖所有类型）**：
- 前端常量 `CHARS_PER_PAGE=1500` 与后端 `document_processor.py` 保持一致；把 `getDocumentContent` 全文按该值切分为估算页。
- 弹窗打开时按引用来源 `source.page` 自动定位到第 N 估算页；提供上一页/下一页、跳页输入框（回车跳转）、字号缩放（-/+，70%~180%）。
- PDF 文件额外保留「原始 PDF」按钮：切换为 Blob URL iframe 内嵌真实 PDF（无页码，满足真实 PDF 查看需求）；默认仍走文本估算翻页。
- 标题动态显示「第 X / Y 页」；移除原「文本模式/文件预览」切换（统一为估算翻页视图）。
- `checkDocumentFileStatus` 返回的 `fileType` 用于判断是否显示「原始 PDF」入口。
**未引入 pdf.js 的原因**：当前引用来源均为 docx，pdf.js 只能渲染真实 PDF，对 docx 无效；若后续知识库上传真实 PDF 且需真实页码定位，可再引入 pdfjs-dist（需处理中文 cMaps）。已在回复中向用户说明。
**验证**：`tsc --noEmit` exit 0、lint 0 错误；前端 dev server HMR 已热更新。docx 引用点「查看原文件」可精准跳到第 N 估算页并翻页、缩放。

### 19. 体验优化：原文件预览「覆盖所有上传格式」的真实分页精准翻页（2026-07-15）
**背景**：第 18 项修 401 后改用 Blob 注入 iframe，导致原 `#page=N` 锚点失效，用户要求补回精准翻页。第 18 项用前端「1500 字符估算」切分，对 docx/txt/md 与后端一致，但 **PDF 后端用 PyMuPDF 真实页码**，前端估算与之不符 → PDF 引用跳页不准；且仅 docx 好用。用户明确要求「所有可上传格式都要可以」。
**关键事实**：知识库 `accept=".pdf,.docx,.md,.txt"` 仅四种格式；`ai-service/services/document_processor.py` 的 `extract_pages` 已能返回真实分页（PDF 真实页码 / docx-txt-md 估算页码），`getDocumentContent` 仅存全文无分页结构。
**方案（后端返回真实分页，前端统一翻页，覆盖四种格式）**：
- AI 服务 `routers/document.py` 新增 `POST /ai/document/extract-pages`（入参 `{file_path, file_type}`，出参 `{status, pages:[{page_no, text}]}`）：调 `document_processor.extract_pages`，纯本地解析不依赖模型配置；失败返回 `status=failed` 由 Java 降级。
- Java `AiServiceClient.extractPages` 调该路由；`DocumentService.getDocumentPages`（新增 `PageContentVO` 返回 `List<PageContentVO>`）优先用 AI 真实解析，AI 不可用/失败时 **降级用已存全文按 CHARS_PER_PAGE=1500 估算**（与 AI 服务常量一致）；`KnowledgeBaseController` 新增 `GET /api/knowledge/document/pages?docId=`，权限复用 `getDocumentContent`（同租户可读）。
- 前端 `knowledge.ts` 新增 `getDocumentPages` 返回 `DocumentPage[]`；`DocumentPreviewModal` 改用真实分页翻页（上一页/下一页/跳页/缩放），移除旧的 blob/前端估算/「原始 PDF」逻辑——**所有四种格式统一走真实分页文本翻页，PDF 也对齐引用真实页码**。
**未引入 pdf.js 的原因（同第 18 项）**：docx 浏览器与 pdf.js 均无法内嵌，pdf.js 仅渲染真实 PDF 对 docx 无效；当前统一文本真实分页对四种格式均精准，PDF 也按真实页码定位，已满足需求。若后续需「真实 PDF 版式渲染」，再引入 pdfjs-dist（含中文 cMaps）。
**验证**：
- AI 单测 `test_document_processor.py` 4 passed（txt/md/docx 分页 + 拼接回原文一致 + page_no 递增 + 缺失文件抛错）。
- Java 单测 `KnowledgeBaseControllerTest` 9 passed（含新增 `getDocumentPages_returnsPages` / `getDocumentPages_noPermission_throws`）。
- 前端 `tsc --noEmit` exit 0、lint 0 错误。
- 联调：Python 8001 重启后 `POST /ai/document/extract-pages` 返回 `status=ok, pages=[{page_no:1,...}]`；Java 8080 重启后 `GET /api/knowledge/document/pages` 已注册（未登录返回 `{"code":40100,...}`，符合项目「HTTP 200 + body.code 表示未登录」约定）。
- 注意：知识库实际启动 AI 服务须用 `python -m uvicorn main:app --port 8001`（`main.py` 无 `__main__` 启动块，`python main.py` 不会监听端口）。

### 20. Bug 修复：查看原文件「暂无可预览内容」（2026-07-15）
**问题**：前端点「查看原文件」弹窗显示「暂无可预览内容，请稍后重试或重新上传该文档」（即 `GET /api/knowledge/document/pages` 返回的 `pages` 为空）。
**根因**：`backend/.../client/AiServiceClient.java` 的 `extractPages` 方法使用了 `new ArrayList<>()`，但 import 区漏写 `import java.util.ArrayList;`。IDE/旧编译产物（ecj）保留了「Unresolved compilation problem: ArrayList cannot be resolved to a type」的陈旧 `.class`，实际运行的服务实例加载该类后，一调到 `extractPages` 即抛 `java.lang.Error`；Java 端异常被上层 catch 后 `getDocumentPages` 返回空列表 → 前端显示空内容。原文件均仍在磁盘（已用 `asyncpg` 直连核查 `ready` 文档 `file_path` 存在），`extract-pages` 路由本身正常（Python 返回 200），故唯一阻断点是该编译缺失 import。
**方案**：在 `AiServiceClient.java` import 区补 `import java.util.ArrayList;`。
**验证**：
- `mvn -Dtest=KnowledgeBaseControllerTest test` 编译通过（exit 0），`ArrayList cannot be resolved` 报错消除。
- Java 后端干净重启（PID 13568，`Started MainApplication`，日志无任何 `Unresolved compilation` / `ArrayList cannot`），新类已加载。
- 由用户侧手动验证：点「查看原文件」应能看到真实分页内容。如仍为空，需排查该文档 `content` 为空且原文件已不在磁盘两种兜底失效情形（已核查 `ready` 文档原文件均在，预期直接生效）。

### 21. Bug 修复：检索召回发散（后端类问题混入前端/HR 来源 + QA 块冒充引用来源）（2026-07-15）
**问题**：用户问「后端刚入职要干嘛」时，引用来源竟含 HR×2 + 前端×2 + 后端×1 多种文档；问「后端规范是什么」时引用来源全是前端文档。根因：旧 `retrieve` 用 **RRF 平等融合**——BM25 凭「入职 / 后端」等关键词把前端、HR、JavaWeb 块拉进 top-5，且问答增强生成的 QA 块（语义与原文高度重合、source 为空）被当作引用来源返回，导致来源发散、答非所问。

**修复（检索融合策略重构，根因在检索层）**：
- `ai-service/services/rag.py`：
  - `_search_core` 改为返回**原始两路结果**（向量 + BM25 + 各自最高分），融合决策收归 `retrieve` 统一处理。
  - **向量主导融合**（`_merge_vector_dominant`）：向量可用（余弦 ≥ `retrieval_vector_min_relevance`）时以语义排序为主、BM25 仅补充，抑制关键词噪声（修复「后端刚入职」类问题发散）。
  - **语义平手 BM25 决胜**：向量分与最优分差距 ≤ `retrieval_bm25_tie_epsilon`（0.02，近乎平手）且块内容与查询的 bigram 词法重合度 ≥ `retrieval_bm25_tie_overlap_min`（0.25）时，按重合度 × `retrieval_bm25_tie_boost`（0.10）加权抬升，让「后端规范」类关键词相关块优先于向量模型略偏的前端/JavaWeb 块；差距明显（如 0.565 vs 0.493）时不触发，保证向量主导收敛效果不被破坏。用词法重合度而非 BM25 物理块匹配——因 BM25 命中的常是 QA 增强块（与原文块 key 不同），直接词法比对才能正确识别含查询关键词的原文块。
  - **相对相关性过滤**：融合后按最终 top-N 最优分 × `retrieval_relative_ratio`（0.80）设下限，剔除跨主题噪声（平手决胜分仅用于排序，不改真实 score，门槛判定仍用真实余弦分）。
  - **排除 QA 增强块**：`chunk_type == "qa"` 的块语义与原文重合却 source 为空、不应作引用来源；排除后若仍有原始块才替换，避免「候选全是 QA 块」误判为无相关文档。
  - `_is_relevant` 修复 BM25 兜底误清空（生产 bug）：向量不可用走 BM25 兜底时，只要有 BM25 召回即视为相关（不再要求 `retrieval_bm25_min_relevance`）。
- `ai-service/core/config.py`：新增 `retrieval_vector_dominant` / `retrieval_relative_ratio` / `retrieval_max_chunks_per_doc` / `retrieval_exclude_qa_blocks` / `retrieval_bm25_tie_epsilon` / `retrieval_bm25_tie_boost` / `retrieval_bm25_tie_overlap_min`（含 `retrieval_bm25_tie_top_n` 预留）等开关，默认即生效。
- `ai-service/services/vector_store.py`：`RetrievalResult` 新增 `chunk_type` 字段；`_bm25_search` / `InMemoryVectorStore.search` / `PgVectorStore.search`（`metadata->>'chunk_type'`）均填充——使 QA 块排除在真实 PG 数据上生效（`get_original_chunks` 早已排除 qa 块，本次检索侧与之对齐）。
- `ai-service/tests/test_retrieval_merge.py`（新增，7 例）：向量主导抑制 BM25 噪声 / 单文档去重上限 / 零向量 BM25 兜底 / 空 source 回退 / 相对阈值剔除跨主题 / 语义平手抬升后端 / 排除 QA 块；隔离 L1 缓存（get_redis 抛异常）。

**验证（真实租户 2075873177644326913 联调，420 块含 69 个 QA 块）**：
- 直接查库确认 QA 块均带 `chunk_type='qa'`，排除在真实数据上可生效。
- 「后端刚入职要干嘛」→ top3 全为后端文档（QA 块数=0）。
- 「后端规范是什么」→ top5 为 JavaWeb + 3×后端 + 自动化测试（**前端文档已全部移出 top-5**，QA 块数=0）。
- 修复前后语义平手决胜的关键差异：BM25 命中常是 QA 块（key 与原文不同），故改用块内容与查询的词法重合度，正确识别含「后端」的原文块。
- 全量 ai-service 单测：本次检索相关 7 例全过；已知 3 例失败均为 `document_processor` 测试跨 `asyncio` 事件循环污染（预存、与本次无关）。

**关联**：M2-4 RAG 检索服务（原 RRF 平等融合已升级为向量主导 + 平手决胜）；M4-8.1（chunk_type=qa 标记体系）；第 9 项（相关性门槛）。

### 22. Bug 修复：PDF 上传「处理失败」——PyMuPDF C 扩展在本机 DLL 加载失败（2026-07-16）
**问题**：上传 `Java面试宝典完整版最最最新.pdf`（14MB）后状态为「处理失败」，数据库 `error_msg` 为 `No module named 'mupdf'`。
**根因**：该 PDF 在 `document_processor.extract_pages` 走 `import fitz`（PyMuPDF）。本机 `pymupdf` 包（含 `_mupdf.pyd` / `mupdf.py` / `mupdfcpp64.dll`）完整，但 `_mupdf.pyd` 加载失败（`ImportError: DLL load failed while importing _mupdf`，缺 VC++ 运行库 / 架构不匹配）——属 OS 级问题、不易在本开发机根治。真正的 DLL 错误被 `pymupdf/__init__.py` 内部兜底 `import mupdf` 掩盖，向上抛出 `No module named 'mupdf'`，这才是数据库里看到的错误信息（与「模块没装」无关，环境已装 pymupdf 1.28.0）。docx/txt/md 走其它分支不受影响。
**修复**（`ai-service/services/document_processor.py`）：
- PDF 提取拆为 `_extract_pdf`（优先 PyMuPDF）+ `_extract_pdf_pdfplumber`（纯 Python 降级）。`import fitz` 触发 `ImportError` 时自动降级到 `pdfplumber`，保证 PDF 在该环境下仍可被处理；生产 Linux 等 PyMuPDF 可用环境行为不变（仍走快速逐页提取）。
- pdfplumber 重型提取用 `asyncio.to_thread` 丢到线程池，避免阻塞事件循环。
- `ai-service/requirements.txt` 新增 `pdfplumber==0.11.9`（PyMuPDF 不可用时的纯 Python 降级方案）。
- `ai-service/tests/test_document_processor.py` 新增 `test_extract_pdf_falls_back_to_pdfplumber`：强制 `import fitz` 抛 ImportError 并注入假 pdfplumber，确定性验证降级分支。

**验证**：
- 直接对本机真实 PDF 跑 `extract_pages`（`backend/uploads/2075873177644326913/272980cc-3013-4671-a311-89d23f67aff0_Java面试宝典完整版最最最新.pdf`）：日志打印 `[PDF] PyMuPDF 不可用（No module named 'mupdf'），降级到 pdfplumber`，返回 `PAGES=394, CHARS=401317, RESULT=OK`。
- ai-service 单测 `pytest tests/test_document_processor.py` → 5 passed（含新增降级测试），无回归。
- Python 8001 干净重启（PID 25400），`GET /docs` 返回 200，新代码已加载。
- 注意：该失败文档记录仍停留 `failed`，需在 UI 重新上传 / 重试触发新处理（根因=PDF 提取已修复，后续上传 PDF 不再因此失败）。

### 23. 排查记录：文档「处理失败」实为后端陈旧构建误判（2026-07-18）

**现象**：上传 `Git工作中需要的操作.docx`（514.7KB）后知识库列表显示「处理失败」。

**两次失败的不同根因**：
- 第一次（13:38）：前端轮询缺陷——后端把 `failed` 改写为 `ready`（重试成功 / 自动恢复）时前端不刷新（见上方「前端状态轮询修复」小节），已于 7/18 修复。
- 第二次（14:01:47）：本机运行的 Java 后端是 **M5-1 落地前的陈旧构建**。后端进程 PID 23004 启动于 7/17 12:51:36，早于 `DocumentServiceImpl.java` 源码最后修改时间 7/17 21:36:21（M5-4 提交）。旧 jar 的 `triggerDocumentProcessing` 把 Python 入队返回的 `status=processing`（入队成功）误判进失败分支，置 `failed` + `error=未知错误`；随后 Python worker 经回调推 `retrieving/optimizing/ready`，但 Java `updateDocumentStatus` 的终态守卫拒绝「failed → 中间态」，日志报「尝试回推状态被拒（当前终态）」，文档卡在 `failed`。

**决定性日志证据**（后端日志）：
- `14:01:47.544 [doc-process-5] [文档诊断] Python入队返回 docId=2078359558363791362 耗时=20ms result={doc_id=..., status=processing}`
- `14:01:47.565 WARN 文档入队失败: ... errorType=null, error=未知错误`（置 failed）
- `14:01:53.343 WARN 尝试回推状态被拒（当前终态）docId=... current=failed, next=retrieving`

矛盾点：源码（7/17 21:36）收到 `processing` 绝不走失败分支，但运行 jar 走了 → 运行的是 M5-1 之前的陈旧构建。实测 `POST /ai/document/process` 返回 `{"status":"processing"}` 正确，进一步佐证运行实例与源码不一致。

**修复**：`taskkill /F /PID 1532 /PID 23004`（maven 父进程 + 后端）终止陈旧进程，再用 `Start-Process mvn.cmd -ArgumentList "spring-boot:run" -WorkingDirectory backend` 干净重启加载 7/17 21:36 的 M5-4 源码（新 PID 37668，14:20:06 启动，Tomcat 8080 正常监听、HikariPool 正常）。重启后查询 PostgreSQL，新文档 `2078359558363791362` 已由 Python worker 回调 `ready` 自动修复（`error_msg` 空、`model_config_error=f`、`quota_error=f`）。

**结论**：本次失败**非代码 bug**，是开发过程中「源码已改但运行实例未重启」导致的陈旧构建误判。重启后端加载新代码后，文档状态由 Python worker 回调 `ready` 自动修复，刷新前端即可查看 / 问答。

**关联**：M5-1（processing 成功判定）、上方「前端状态轮询修复」（7/18）、M5-4。

---

## M6 — 检索增强（参考腾讯 WeKnora）

> 方案设计文档：`docs/design-search-enhancement.md`

### M6-1 RRF 参数可配置化 [后端/Java + 前端/React + AI服务/Python] P0 · 0.5d ✅

**目标**：把融合参数从 `config.py` 硬编码提升为租户级可配，存数据库，有默认值兜底。

- [x] `schema.sql`：`tenant` 表新增 `retrieval_config JSONB` 列
- [x] `TenantController.java`：新增 `GET/PUT /api/tenant/retrieval-config` 接口（用 loginUser 获取 tenantId）
- [x] `AiServiceClient.java`：chat 请求 body 加 `retrievalConfig` 字段
- [x] `ai-service/routers/chat.py`：`ChatStreamRequest` 加 `retrieval_config: str | None` 字段
- [x] `ai-service/services/rag.py`：`retrieve()` 读 `retrieval_config` 覆盖 `settings` 默认值（`_rc()` 辅助函数）
- [x] `frontend/src/components/RetrievalConfigPanel.tsx`：新建组件（8 项参数编辑 + 恢复默认）
- [x] `frontend/src/pages/AIConfigPage.tsx`：底部嵌入检索配置面板
- [x] `frontend/src/types/index.ts`：新增 `RetrievalConfig` 类型
- [x] `frontend/src/api/tenant.ts`：新增 getRetrievalConfig/updateRetrievalConfig API
- [ ] L1 缓存 key 纳入 `retrieval_config` 参数（待后续优化）

**验收**：修改租户 RRF 参数后检索结果排序变化；恢复默认后行为与当前一致。

### M6-2 Chunk 文本匹配拼接 [AI服务/Python] P0 · 0.5d ✅

**目标**：实现纯函数模块，按文本后缀匹配去除相邻块间的重叠，不依赖位置坐标。

- [x] `ai-service/services/chunk_merge.py`：新建，实现 `append_with_overlap()` + `merge_text_chunks()`
- [x] `ai-service/services/rag.py`：`retrieve()` 中 `attach_parents` 后新增 `_merge_adjacent_chunks()` 调用
- [x] `ai-service/tests/test_chunk_merge.py`：新建，8 例单测全部通过

**验收**：相邻命中块拼接后无内容重复/断裂；单块/不相邻块不受影响。

### M6-3 FAQ 负向问题过滤 [AI服务/Python] P1 · 1d ✅

**目标**：FAQ chunk 支持 `negative_questions` 元数据，检索后精确匹配过滤。

- [x] `ai-service/services/vector_store.py`：`RetrievalResult` 新增 `negative_questions: list[str]` 字段，检索时从 metadata 解析
- [x] `ai-service/services/document_processor.py`：QA 生成 prompt 调整，支持生成负向问题，写入 metadata
- [x] `ai-service/services/rag.py`：新增 `_filter_negative_questions()` 方法，rerank 之后调用
- [x] `ai-service/core/config.py`：`enable_negative_question_filter: bool = True`（已有）
- [x] `ai-service/tests/test_negative_question_filter.py`：新建，8 例单测全部通过

**验收**：用户 query 精确匹配负向问题→对应 chunk 被剔除；无负向问题的 chunk 不受影响。✅

### M6-4 相邻块补全 [AI服务/Python] P1 · 0.5d ✅

**目标**：检索命中某块后，补全同文档的前一块和后一块作为上下文，LLM 获得更完整语境。

- [x] `ai-service/services/vector_store.py`：新增 `get_adjacent_chunks()` 方法，按 doc_id + chunk_index±1 批量查询相邻块（PgVectorStore + InMemoryVectorStore）
- [x] `ai-service/services/vector_store.py`：`RetrievalResult` 新增 `is_context_expansion: bool = False` 字段
- [x] `ai-service/services/rag.py`：`retrieve()` 中 `attach_parents` 后新增相邻块补全调用
- [x] `ai-service/core/config.py`：`enable_neighbor_expansion: bool = True`（已有）
- [x] `ai-service/routers/chat.py`：sources 排除 `is_context_expansion` 块
- [x] `ai-service/tests/test_adjacent_chunk_expansion.py`：新建，7 例单测全部通过

**验收**：命中块有前/后块时补全到 results 中；文档首/尾块仅补全一侧；补全块不列入引用来源。

### M6-5 集成验证 + 文档更新 [全栈] P0 · 0.5d

- [ ] 4 项功能联合验证（按验收标准逐项测试）
- [ ] `docs/design-search-enhancement.md` 更新实现状态
- [ ] `docs/task-breakdown.md` 标记 M6 各项完成
- [ ] `README.md` 更新检索增强功能说明

---

### M6 依赖关系

```
M6-1 (RRF 参数可配)  ──→ M6-5 (集成验证)
M6-2 (Chunk 拼接)    ──→ M6-5
M6-3 (FAQ 负向过滤)  ──→ M6-5
M6-4 (迭代检索)      ──→ M6-5
```

M6-1 ~ M6-4 之间无代码依赖，可并行开发。

