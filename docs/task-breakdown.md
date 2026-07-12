# 熊答 — 任务功能拆分

> 按里程碑分阶段，标注技术栈、优先级、预估工时、依赖关系。

## 里程碑规划

| 里程碑 | 目标 | 范围 |
|---|---|---|
| **M1 - MVP** | 能注册登录、上传文档、基本问答 | 认证 + 知识库基础 + 基础问答 |
| **M2 - 核心** | RAG 检索 + 流式回答 + 引用溯源 | RAG 检索 + SSE 流式 + 会话管理 |
| **M3 - 管理** | 多角色权限 + 成员管理 + AI配置 | RBAC + 成员 + 配置 + 审计 |
| **M4 - 增强** | Agent 推理 + 共享/个人库 + UI 精细化 | Agent + 权限细化 + 体验优化 |

---

## M1 — MVP（能跑通的核心链路）

### M1-1 数据库初始化 [后端/Java] P0 · 0.5d ✅ 已完成

- ~~配置 PostgreSQL 连接（application.yml）~~
- ~~JPA ddl-auto 建表验证~~ → 改为 **MyBatis-Plus + 手动建表**
- ~~初始化默认数据（super_admin 账号）~~ → TODO（注册流程自动创建）
- ~~Redis 连接验证~~

**实际完成内容：**
- ✅ Docker 启动 PostgreSQL 16 + Redis 7（复用 WeKnora 镜像 paradedb/paradedb:v0.22.2-pg17）
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
- ✅ useAuth Hook（login/register/logout，注册成功自动登录，JWT 存 localStorage）
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

### M3-1 RBAC 权限细化 [后端/Java] P0 · 1d

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

参考腾讯 WeKnora 的 RBAC 思路（租户角色矩阵 + KB 归属数据级权限，而非纯 URL 级），落地本项目简化模型：

- ✅ 新增 `service/KbPermission.java`：集中 RBAC 数据级规则
  - 角色模型：`tenant_admin` / `super_admin`（写权限）/ `member`（只读）
  - 共享库（`shared`）：仅 `tenant_admin` / `super_admin` 可写（创建、上传、删除文档）
  - 个人库（`personal`）：仅 `owner`（KB 的 `owner_id`）可写
  - 读操作（列表、问答）对租户内全员开放，不在此限制
  - `super_admin` 作为跨租户超管自动放行（对齐 WeKnora 的 SystemAdmin 思路）
- ✅ `KnowledgeBaseService/Impl.createKnowledgeBase`：签名改为传入 `User`，创建共享库时校验 `KbPermission.assertCanCreate(scope, role)`；owner 取 `user.getId()`
- ✅ `DocumentService/Impl.uploadDocument` / `deleteDocument`：签名改为传入 `User`，写前经 `KbPermission.assertCanWrite(kb, userId, role)` 校验（注入 `KnowledgeBaseService` 查 KB scope/owner）
- ✅ `KnowledgeBaseController`：创建/上传/删除三处写操作透传 `loginUser`
- ✅ 单元测试 37 个全过：`KbPermissionTest`(14) + `KnowledgeBaseServiceImplTest`(10) + `DocumentServiceImplTest`(13)，覆盖共享库/个人库的创建与写权限、owner 匹配、跨租户隔离
- **说明**：WeKnora 的 4 级租户角色（Owner→Admin→Contributor→Viewer）与跨租户 Org 共享（`kb_share` 表 + 3-D 权限帽）对当前需求过度设计，本项目采用"共享库/个人库 + 3 角色"简化模型，核心思路（租户角色 + 资源归属的数据级 RBAC）保持一致

**租户隔离强化（2026-07-12，对齐 WeKnora own-KB 判定）**

复核发现写权限缺口：`assertCanWrite` 仅校验 owner/role，**未校验调用者 tenant 与 KB tenant 一致**，导致租户 A 的 `tenant_admin` 可越权写入租户 B 的共享库（读隔离 `tenant_id` 过滤已完整，删除文档已有 `tenantId` 校验故安全，仅上传漏了）。

对齐 WeKnora `kb_access.go` 第一步 `kb.TenantID == tenantID`（own-KB 优先）修复：
- ✅ `KbPermission.assertCanWrite` 新增 `callerTenantId` 参数，判定顺序：**super_admin 跨租户完全放行 → 其余角色必须 `callerTenantId == kb.tenantId`（租户隔离第一维度）→ 共享库仅 tenant_admin / 个人库仅 owner**
- ✅ `DocumentServiceImpl.uploadDocument` / `deleteDocument` 调用处补传 `tenantId`（与删除已有的 doc 级 tenant 校验形成双保险）
- ✅ 单测增量：KbPermissionTest 14→17（新增跨租户 tenant_admin 拒 / member 个人库跨租户拒 / super_admin 跨租户放），DocumentServiceImplTest 13→14（新增 `uploadDocument_tenantAdminCrossTenant_denied`）；三件套共 41 全过
- **结论**：至此 RBAC 具备完整的多租户写安全（tenant 作为权限第一维度，与 WeKnora 一致）

---

### M3-2 成员管理 [全栈] P1 · 1.5d

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
    - `updateUser`：已做 `!tenantId.equals(user.getTenantId())` 跨租户拒绝 ✅，但**缺防护**：① 不能把唯一/自己 `tenant_admin` 降级为 `member`（否则租户无管理员锁死）；② 不能改 `super_admin` 角色；③ 改角色未限范围
    - `inviteMember`：校验邮箱唯一 → 直接建用户（默认密码 `123456`，有 `TODO` 邀请流程）；注意实现是「直接创建账号」而非设计稿所说的「生成邀请链接」
  - DTO 已存在：`UserUpdateRequest`、`UserInviteRequest`
  - **后端缺**：三个方法的单测
- **前端 `frontend/src/pages/MembersPage.tsx` 是静态假数据**
  - `MEMBERS` 写死、未接任何 API
  - 角色用 `admin`/`member` 简写，而非后端枚举 `tenant_admin`/`member`/`super_admin`（需对齐 `UserConstant`）
  - 「邀请成员」按钮、「设为管理员」、「移除」**均无真实交互**（无 onClick / 未调接口）
  - 权限矩阵为静态展示（可保留）

**实现记录（2026-07-12）**

两项关键决策已与用户确认：**邀请采用「生成邀请链接」（对齐 WeKnora share-link）** + **移除采用「软删除」（对齐 WeKnora RemoveMember）**。

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

---

### M3-3 AI 模型配置 [全栈] P1 · 1d

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
  - ⬜ 待开发：Java 保存接口接通；Python 从 Java 取配置并**取消静默降级**（无 Key 造假向量/假回答）、调用失败抛可识别「模型配置错误」；前端「上传文档失败」与「对话失败」按错误类型提示「模型配置不正确，请重新配置」并跳转；`AIConfigPage` 保存按钮接通。
- **依赖**: M1-2
- **产出**: 可在前端配置 AI 模型，且配置错误能在运行时被识别并提示重配

---

### M3-4 审计日志 [全栈] P1 · 1d

- **Java**: 审计日志记录切面（AOP）
  - 登录/登出、文档上传/删除、配置修改、成员变更
- **Java**: 审计日志查询接口（分页、筛选）
- **前端**: 审计日志页面
  - 筛选栏（操作类型、时间范围、用户）
  - 日志列表表格
  - 展开查看 JSON 详情
- **依赖**: M3-1
- **产出**: 可查看操作审计记录

---

### M3-5 平台超管功能 [后端/Java] P2 · 1.5d

- 租户管理：列表、创建、停用、配额设置
- 平台配置：全局默认模型配置
- 全局审计日志（跨租户）
- **依赖**: M3-1
- **产出**: 平台超管可管理系统

---

**M3 合计: ~6 天**

---

## M4 — Agent 推理 + 体验增强

### M4-1 ReAct Agent 多步推理 [Python] P1 · 2d

- LangChain Agent 实现
  - 工具：知识库检索、网络搜索
  - ReAct 循环：Thought → Action → Observation
  - 多步推理（复杂问题分解）
- `/ai/chat/stream` 增加 agent 模式
- 前端展示推理步骤
- **依赖**: M2-5
- **产出**: 复杂问题多步推理回答

---

### M4-2 共享/个人知识库完善 [全栈] P1 · 1d

- **Java**: 知识库 scope 权限校验细化
- **Java**: 共享库仅 tenant_admin 可上传/删除
- **前端**: 共享库只读模式（普通成员隐藏上传/删除按钮）
- **前端**: 个人库完整管理
- **依赖**: M3-1
- **产出**: 共享/个人库权限正确

---

### M4-3 问答模式切换 [全栈] P2 · 0.5d

- 知识库问答模式（RAG）
- 网络搜索模式（联网搜索 + LLM 总结）
- 前端模式切换按钮
- **依赖**: M4-1
- **产出**: 可切换问答模式

---

### M4-4 文档原文查看 [全栈] P2 · 1d

- **Java**: 文档预览接口（PDF 在线预览）
- **前端**: 引用来源"查看原文" → 弹窗显示 PDF 对应页
- **依赖**: M2-5
- **产出**: 点击引用可查看原文

---

### M4-5 登录页动效 [前端] P2 · 1d

- Canvas 粒子系统（90 粒子 + 连线 + 鼠标交互）
- 极光光晕动画
- 毛玻璃卡片
- 密码强度检测（4 格强度条）
- 社交登录按钮（GitHub / Google）
- **依赖**: M1-3
- **产出**: 登录页与 UI 设计稿一致

---

### M4-6 问答页 UI 精细化 [前端] P2 · 1d ✅ 完成

- ~~推荐问题胶囊~~ ✅
- ~~知识库选择标签~~ ✅
- ~~工具栏（智能推理下拉、模型选择、附件按钮）~~ ✅
- ~~输入框自动增高~~ ✅
- ~~对话气泡（用户右对齐 / AI 左对齐卡片）~~ ✅
- **依赖**: M2-6
- **产出**: 问答页与 UI 设计稿一致

> 注：基础 UI 框架已在 M1-8.5 完成；M2-6 会话管理完成后补充了 Markdown 渲染、引用来源卡片，本次收尾补齐输入框自动增高，M4-6 全部完成。

---

### M4-7 通知与提示 [前端] P2 · 0.5d

- Toast 通知组件（成功/失败/警告）
- 上传进度条
- 加载状态骨架屏
- 空状态提示
- **依赖**: M1-9
- **产出**: 全局交互反馈完善

---

### M4-8 部署与 DevOps [运维] P2 · 1.5d

- Docker Compose（PostgreSQL + Redis + Milvus + Java + Python + Nginx）
- Java Dockerfile（多阶段构建）
- Python Dockerfile
- 前端构建产物 Nginx 部署
- 环境变量管理（.env.production）
- **依赖**: M3 全部
- **产出**: 一键 docker-compose up 启动

---

**M4 合计: ~10.5 天**

---

## M2-7 三层 Redis 缓存（对齐 WeKnora，2026-07-12）

> 需求：参照腾讯 WeKnora，实现三层 Redis 缓存——先写 Redis 再落库、每个会话带 TTL、重复问题直接走缓存跳过检索与数据库。

**WeKnora 真实架构纠正**：WeKnora 是 **Go 后端 + Python 文档解析微服务**，缓存层（L1/L2/L3）都在 Go 后端内。本项目是 Java 后端持有会话 + Python AI 服务做 RAG 编排/LLM，因此映射为：L1/L2 落 Python，L3 落 Java。其 `session:{sid}:stream`（Redis Stream 存 SSE 事件）属 Go 后端原生能力，跨服务架构下收益低、复杂，**本期不照搬**。

**三层缓存落地**

| 层 | key | 位置 | 缓存内容 | TTL | 命中后跳过 |
|---|---|---|---|---|---|
| L1 检索结果 | `retrieval:{tenant_id}:{q_hash}` | Python `rag.py` | 混合检索+Rerank 结果 | 3600s（文档变更主动清） | 向量/BM25/RRF/Rerank |
| L2 嵌入向量 | `embedding:{text_hash}:{model}` | Python `embedding.py` | 文本向量 | 86400s | Embedding API |
| L3 会话状态 | `chat:conv:{conv_id}`（Redis List） | Java `ChatServiceImpl` | 最近 50 条消息 | 1800s | DB 历史查询 |

- **L1 跨会话 tenant 级**：同一租户任何人问相同问题命中，跳过检索阶段（仍走 LLM 实时生成，答案不过时，与 WeKnora 一致）。
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

**关联**：M2 RAG 核心检索；WeKnora 三层缓存。

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

M3 全部 ──→ M4-8 部署
```
