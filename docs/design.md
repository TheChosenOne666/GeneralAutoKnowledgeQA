# 熊答 — 方案设计文档

> 技术架构与系统设计方案

## 1. 系统架构

### 1.1 整体架构

```
┌─────────────────────────────────────────────┐
│                  前端（React）                │
│         TailwindCSS · SSE · Canvas           │
└──────────────────┬──────────────────────────┘
                   │ HTTP / SSE (port 5173 → 8080)
┌──────────────────▼──────────────────────────┐
│           Java 后端（Spring Boot）            │
│    鉴权 · CRUD · 业务逻辑 · SSE 透传          │
└──────────────┬───────────────┬──────────────┘
               │               │ HTTP (port 8080 → 8001)
               │   ┌───────────▼──────────────┐
               │   │   Python AI 服务          │
               │   │   FastAPI + LangChain     │
               │   │   RAG检索 · Agent推理     │
               │   │   文档处理 · 向量化       │
               │   └───────────┬──────────────┘
               │               │
┌──────────────▼───────────────▼──────────────┐
│               数据存储层                      │
│  ┌────────┐ ┌────────┐ ┌─────────────────┐  │
│  │PostgreSQL│ │ Redis  │ │ 向量数据库       │  │
│  │业务数据  │ │缓存/队列│ │ Milvus/Qdrant  │  │
│  └────────┘ └────────┘ └─────────────────┘  │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│              外部 AI 服务                    │
│  ┌─────────┐ ┌───────────┐ ┌─────────────┐  │
│  │   LLM   │ │ Embedding │ │   Rerank    │  │
│  │ 豆包/DS  │ │ Doubao    │ │  可选配置    │  │
│  └─────────┘ └───────────┘ └─────────────┘  │
└─────────────────────────────────────────────┘
```

### 1.2 技术选型

| 层级 | 技术 | 说明 |
|---|---|---|
| 前端 | React + TypeScript | SPA 单页应用 |
| UI 框架 | TailwindCSS | 原子化 CSS，浅绿主题 |
| **后端** | **Java 17 + Spring Boot 3.4** | 业务逻辑、认证、CRUD |
| 后端 ORM | **MyBatis-Plus 3.5** | BaseMapper + ServiceImpl + 分页插件 |
| 后端安全 | **JWT + @AuthCheck AOP** | 自定义注解 + 切面鉴权（非 Spring Security） |
| 后端 HTTP 客户端 | WebFlux (WebClient) | 调用 Python AI 服务 + SSE 透传 |
| 后端文档 | Knife4j (OpenAPI 3) | API 文档 |
| ID 策略 | **Long（雪花算法 ASSIGN_ID）** | 非 UUID |
| 密码加密 | **MD5 + 盐值** | 非 BCrypt |
| 逻辑删除 | **@TableLogic isDelete** | 0 未删 / 1 已删 |
| 统一响应 | **BaseResponse + ResultUtils + ErrorCode** | code/data/message |
| 异常处理 | **BusinessException + ThrowUtils + GlobalExceptionHandler** | 全局兜底 |
| 业务数据库 | PostgreSQL | 多租户行级隔离 |
| 缓存 | Redis | 三层缓存（L3 会话 / L1 检索 / L2 嵌入） |
| **AI 服务** | **Python + FastAPI**（RAG 检索 / 自研 ReAct Agent 推理 / 文档处理；LangChain 为可选依赖） | RAG 检索、Agent 推理、文档处理 |
| 向量数据库 | Postgres pgvector（默认持久化，对标业界成熟方案）/ Milvus（可选）/ 内存（演示） | 文档向量存储与检索，重启不丢知识 |
| 文档解析 | PyMuPDF（PDF，C 扩展不可用降级 pdfplumber）/ python-docx（DOCX）/ MD·TXT 直读 + LangChain RecursiveCharacterTextSplitter 分块 | 未引入 unstructured |

---

## 2. 多租户设计

### 2.1 隔离策略

采用 **共享数据库 + 行级隔离**：

```sql
-- 每张业务表都带 tenant_id，MyBatis-Plus @TableName 映射
-- 表名用单数形式（tenant / user / knowledge_base / document ...）
-- 字段名用驼峰（map_underscore_to_camel_case: false）
CREATE TABLE knowledge_base (
    id              BIGINT PRIMARY KEY,       -- 雪花算法 ASSIGN_ID
    tenant_id       BIGINT NOT NULL,
    name            VARCHAR(200),
    scope           VARCHAR(20) DEFAULT 'personal',  -- shared / personal
    owner_id        BIGINT,
    document_count  INTEGER DEFAULT 0,
    create_time     TIMESTAMP DEFAULT NOW(),
    update_time     TIMESTAMP DEFAULT NOW(),
    is_delete       INTEGER DEFAULT 0         -- 逻辑删除 0/1
);
```

### 2.2 租户上下文

```java
// UserService.getLoginUser() 从 JWT 解析 tenant_id
public User getLoginUser(HttpServletRequest request) {
    String token = request.getHeader("Authorization").substring(7);
    Claims claims = jwtUtil.parseToken(token);
    Long userId = jwtUtil.getUserId(claims);
    return userMapper.selectById(userId); // 自动带 tenant_id
}

// 查询时手动过滤 tenant_id（MyBatis-Plus QueryWrapper）
QueryWrapper<KnowledgeBase> queryWrapper = new QueryWrapper<>();
queryWrapper.eq("tenant_id", tenantId);
List<KnowledgeBase> kbs = knowledgeBaseService.list(queryWrapper);
```

---

## 3. 权限设计（RBAC）

### 3.1 角色模型

```
super_admin ── 平台级
    │
    ├── tenant_admin ── 租户级
    │       │
    │       └── member ── 用户级
```

### 3.2 权限控制

```java
// 角色枚举
public enum UserRoleEnum {
    MEMBER("member", "普通成员"),
    TENANT_ADMIN("tenant_admin", "租户管理员"),
    SUPER_ADMIN("super_admin", "平台超管");
}

// 自定义注解 + AOP 切面鉴权
@AuthCheck(mustRole = {UserConstant.TENANT_ADMIN_ROLE})
@PostMapping("/update")
public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest request, HttpServletRequest request) {
    // ...
}

// AOP 切面拦截 @AuthCheck
@Around("@annotation(authCheck)")
public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
    String[] mustRoles = authCheck.mustRole();
    User loginUser = userService.getLoginUser(request);
    // 校验角色...
}

// 数据级权限：共享知识库
// member → 只读（前端隐藏上传/删除按钮）
// tenant_admin → 读写
```

> **M4-2 共享/个人知识库（2026-07-14 完成）**：前述数据级权限已落地。
> - **后端（M3-1 已完成并单测覆盖）**：`service/KbPermission.java` 的 `assertCanCreate` / `assertCanWrite` 集中实现——共享库仅 `tenant_admin` / `super_admin` 可创建与写入（上传/删除文档），个人库仅 owner 可写，超管跨租户完全放行，并叠加租户隔离第一维度；`DocumentServiceImpl.uploadDocument` / `deleteDocument` 与 `KnowledgeBaseServiceImpl.createKnowledgeBase` 均调用校验，`KbPermissionTest` 12 例覆盖全部路径。
> - **前端（本次补齐）**：`KnowledgeBasePage.tsx` 引入 `useAuth()` 取当前角色，计算 `canWrite`（共享库仅管理员为真、个人库全员为真，因个人库列表仅返回本人库）；据此隐藏「新建知识库 / 批量上传 / 批量删除」按钮，普通成员在共享库下呈**只读模式**，并显示横幅提示「仅租户管理员可维护」；个人库保持完整管理（创建/上传/删除）。后端校验为最终防线，前后端一致。

### 3.3 菜单动态渲染

前端根据用户角色动态渲染侧边栏菜单：

```typescript
const menuItems = [
  { key: 'chat', label: '对话', roles: ['member', 'tenant_admin', 'super_admin'] },
  { key: 'knowledge', label: '知识库', roles: ['member', 'tenant_admin'] },
  { key: 'ai-config', label: 'AI模型配置', roles: ['member', 'tenant_admin'] },
  { key: 'members', label: '成员管理', roles: ['tenant_admin'] },
  { key: 'audit', label: '审计日志', roles: ['tenant_admin', 'super_admin'] },
  { key: 'tenant', label: '租户管理', roles: ['super_admin'] },
];
```

### 3.4 平台超管功能（M3-5）

平台超管（`super_admin`，`tenant_id` 为 NULL）可跨租户管理整个系统，所有接口均加 `@AuthCheck(mustRole = SUPER_ADMIN_ROLE)`。

**租户管理**（`TenantController` `/api/tenant`，仅超管）：

- `GET /list`：分页列出全部租户，VO 实时统计 `memberCount` / `docCount`。
- `POST /create`：创建租户，并把指定**已注册**邮箱用户设为该租户首个 `tenant_admin`（对标业界成熟方案 EnsureOwner）；slug 全局唯一；拒绝把 `super_admin` 设为租户管理员。
- `POST /{id}/status`：`active` ↔ `suspended` 启用停用。
- `POST /{id}/quota`：设置成员数 / 文档数上限（`<=0` 视为不限）。

**配额执行（对标业界成熟方案）**：`DocumentServiceImpl.uploadDocument` 与 `UserServiceImpl` 邀请/注册入租户时，校验 `Tenant.maxDocuments` / `maxMembers`，达到上限即拒绝（`OPERATION_ERROR`）。

**平台默认 AI 配置**：`AiConfigController` 新增 `GET/POST /ai-config/platform-default`（仅超管），以 `tenant_id=0` 哨兵行作为全租户兜底；用户配置回退链：`用户级 → 租户级 → 平台级`。前端 `AIConfigPage` 在超管视角下提供「我的配置 / 平台默认配置」作用域切换。

**全局审计日志**：`AuditLogController.listLogs` 对 `super_admin` 走 `listAllLogs`（跨租户），对 `tenant_admin` 走 `listLogsByTenant`（本租户）。

**超管切换租户操作（方案A，对齐 业界 TenantSelector（租户切换））**：平台超管除平台级页面（租户管理 / 全局审计 / 平台默认 AI 配置）外，还常以"某个租户管理员"身份进入其知识库、成员、对话、租户级 AI 配置。采用**租户上下文切换**而非新建跨租户列表页：

- **后端**：`UserServiceImpl.getLoginUser` 解析出登录用户后，若角色为 `super_admin` 且请求头携带 `X-Tenant-ID`（`CommonConstant.TENANT_HEADER`），则将其 `tenantId` 临时覆盖为该请求头值（仅内存对象，不写库）；普通用户忽略该头，防止越权切换租户。`AuthInterceptor` 对 `super_admin` 直接放行全部接口，故切进某租户后可访问其 `tenant_admin` 专属业务接口（如成员管理 `@AuthCheck(mustRole=tenant_admin)`）。
- **前端**：新增 `TenantContext`（`context/TenantContext.tsx`），超管登录后加载全部租户并默认选中 `localStorage` 中已选（否则第一个）；切换器写入 `localStorage` 的 `xiongda_current_tenant`。`api/client.ts` 请求拦截器携带 `X-Tenant-ID` 头。`AppLayout` 仅对超管渲染租户切换器，并将其有效角色映射为可见 `tenant_admin` 菜单（知识库 / 成员管理），同时主内容区以 `currentTenantId` 为 `key` 重挂，触发各业务页重新加载该租户数据；`ChatContext` 在 `currentTenantId` 变化时清空并刷新对话列表。
- **效果**：超管在侧边栏切到「租户A」后，知识库 / 成员管理 / 对话 / AI 模型配置均按租户A展示与操作，复用既有页面与接口，无需新建跨租户列表。

**全局顶部 Toast 提示（2026-07-14）**：新增通用组件 `frontend/src/components/Toast.tsx`，由 `ToastProvider` 挂在 `main.tsx` 应用根部；固定页面顶部居中绿色（成功）/红色（失败）横幅，含图标，3 秒自动消失。登录/注册/邀请成功、AI 配置保存成功均调用 `useToast().success` 在顶部弹出绿色提示；普通用户保存 AI 配置成功后延迟 1.2 秒自动跳转 `/chat`。替代原先卡片内小横幅式的成功提示，统一为页面顶部绿色提示。

---

## 4. 知识库设计

### 4.1 共享 vs 个人

```sql
-- 表名单数，字段驼峰，BIGINT 雪花ID，逻辑删除
CREATE TABLE knowledge_base (
    id              BIGINT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    name            VARCHAR(200) NOT NULL,
    scope           VARCHAR(20) DEFAULT 'personal',
    -- shared: 租户管理员维护，全员可问答
    -- personal: 个人维护，仅自己可问答
    owner_id        BIGINT NOT NULL,
    document_count  INTEGER DEFAULT 0,
    create_time     TIMESTAMP DEFAULT NOW(),
    update_time     TIMESTAMP DEFAULT NOW(),
    is_delete       INTEGER DEFAULT 0
);

CREATE TABLE document (
    id          BIGINT PRIMARY KEY,
    kb_id       BIGINT NOT NULL,
    tenant_id   BIGINT NOT NULL,
    filename    VARCHAR(500) NOT NULL,
    file_type   VARCHAR(20),     -- pdf / docx / md / txt
    file_size   BIGINT,
    status      VARCHAR(20) DEFAULT 'processing',
    -- processing → parsing → retrieving → optimizing(已可检索) → ready / failed
    -- （对齐 业界 finalizing（异步增强）=queryable：向量化完成即可检索，optimizing 仅问答增强后台进行中）
    chunk_count INTEGER DEFAULT 0,
    error_msg   TEXT,
    uploaded_by BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT NOW(),
    update_time TIMESTAMP DEFAULT NOW(),
    is_delete   INTEGER DEFAULT 0
);
```

### 4.2 权限规则

| 操作 | 共享知识库 | 个人知识库 |
|---|---|---|
| 问答 | 全员 | 仅 owner |
| 上传文档 | tenant_admin | owner |
| 删除文档 | tenant_admin | owner |
| 查看列表 | 全员 | 仅 owner |

---

## 5. RAG 检索流程

### 5.1 文档处理流水线

```
上传文件
  │
  ▼
文本提取（PyMuPDF / python-docx / MD·TXT 直读）
  │
  ▼
分块（LangChain RecursiveCharacterTextSplitter）
  └── 固定长度：chunk_size=512，重叠 overlap=50
  │
  ▼
向量化（Embedding，命中 L2 缓存则跳过 API 调用）
  │
  ▼
存储（向量数据库 + 元数据 doc_id / kb_id / tenant_id / source / page）
```

### 5.2 问答检索流程

```
用户提问
  │
  ▼
Query 向量化（Embedding）
  │
  ▼
混合检索
  ├── 向量检索（Top-K=20）
  └── 关键词检索（BM25，Top-K=20）
  │
  ▼
向量主导融合（语义优先，BM25 仅补充不足）
  │
  ▼
LLM 重排（用已配置 LLM 对候选块打 0~1 相关性分并重排，抑制跨领域串味；见下）
  │
  ▼
构建 Prompt（检索结果 + 系统提示 + 对话历史）
  │
  ▼
LLM 生成回答（SSE 流式输出）
  │
  ▼
引用溯源（标注来源文档 + 页码）
```

> **普通问答增强（A 档，2026-07-14，对齐 业界 KnowledgeQA 方案）**：rag 模式检索前对 query 做 LLM 改写（rewrite）+ 召回不足时扩展检索（expansion），提升「措辞不一致」场景的召回鲁棒性。仅作用于 rag 模式（`retrieve(enhance=True)`）；Agent 模式不开启（Agent 内部由 LLM 自生成子查询，二次改写会画蛇添足）。
> - **query rewrite**：`services/query_rewrite.py:rewrite_query` 用一次 LLM 调用把口语化 / 长问句改写成检索友好的关键词短句；模型配置错误（`ModelConfigError`）向上抛出由路由转 `MODEL_CONFIG_ERROR`，其他异常降级用原话检索。
> - **query expansion**：主检索结果数 `< retrieval_expansion_min`（默认 3）时，`expand_query` 生成 1~2 个语义不同角度的扩展 query，分别检索后 RRF 合并兜底；任何异常降级不扩展。
> - 开关：`core/config.py` 的 `enable_query_rewrite` / `enable_query_expansion`（默认开），可独立关闭。`retrieve` 的 L1 缓存 key 仍用用户原话（改写仅提升本次召回，不污染缓存键）；rewrite / expansion 失败均不阻断主流程（配置错误除外）。
> - 注意：rerank 阶段使用改写后的 `search_query` 评估相关性，更贴近实际检索意图。

> **LLM 重排（2026-07-16，对标业界成熟方案 Rerank 跨领域判别意图，复用已配置 LLM）**：弱向量模型对短 query 领域判别力不足（如「后端规范」把前端块余弦分评得比后端块高），纯向量融合无法纠正错误排序。业界方案 依赖 Rerank 精排（cross-encoder + 阈值过滤）解决，但本项目 volcengine rerank 接口非 OpenAI 兼容、无法即插即用。故改用**已配置的 LLM 当重排器**（`services/rag.py` 的 `_rerank_with_llm`）：融合后把 query + 候选块发给 LLM，要求逐块给出 0~1 相关性分数，按分数重排并按 `retrieval_rerank_min_relevance`（默认 **0.40**，2026-07-16 由 0.30 上调，更激进剔除跨主题噪音，保留跨库召回）阈值过滤跨主题块（最优分仍 ≥ 0.15 时保留 top1 兜底）。该方式**不写死任何领域词、不新增服务**（复用 AI 配置页已填的 LLM），跨领域判别比弱向量强得多，且对任意新增领域（如「算法岗」文档）自动生效。调用失败 / 分数不可解析时安全回退到向量融合顺序（不静默吞错）。开关：`core/config.py` 的 `retrieval_rerank_method`（默认 `llm`，可选 `api` 走 OpenAI `/rerank`、`none` 关闭）。
>
> **重排过滤加严（2026-07-17 修复）**：原 `merged = kept[:top_n] if kept else merged[:top_n]` 在「无块过门槛」时会回退展示**全部未过滤结果**，门槛过滤形同虚设（实测问「校招面试重点」却把《后端新员工入职指南》《JavaWeb笔记》等主题无关文档当来源）。已改为 `merged = kept[:top_n]`——重排后只保留过门槛块；`_rerank_with_llm` 提示词同步加严：明确「仅含相同关键词但主题不符（如校招重点 vs 入职指南 / 常规 Java 笔记）的片段必须 ≤0.2」，使无关块被滤除。若加严后仍偏松，可上调 `retrieval_rerank_min_relevance`（0.45~0.55），代价是更难问题的召回可能变窄。

> **重排候选池（2026-07-16 方案A）**：开启重排时，向量/BM25 融合不再直接取 `top_n`（默认 5）送重排，而是先取更大融合池 `retrieval_rerank_top_k`（默认 10），由 LLM 在整个池内精排后取 `top_n`。解决「模糊问句真正相关块未进前 `top_n`、LLM 只在这 `top_n` 内打分致全 0 漏召回」的回归；相关块落在第 6~10 位时能被救回，且不带回跨领域噪声（最终仍按阈值过滤取 `top_n`）。关闭重排（`method=none`）时该池不起作用，直接截断 `top_n`。L1 缓存 key 已纳入 `rerank_top_k` 与 `top_n`，切换后旧缓存自动失效。

> **大纲感知分块与大纲召回（2026-07-16 方案C）**：问「知识架构 / 大纲 / 目录 / 有哪些模块」类问题时，原检索在概述页命中的是泛泛介绍，AI 难以归纳出文档真实章节框架。方案C 不引入父子分块 / GraphRAG，而是**大纲感知分块 + 大纲专用召回路由**：
> - **分块**：`document_processor.chunk_text` 在常规内容块之外，逐页扫描标题行生成 `chunk_type=outline` 的标题块（记录 `section_title`），不破坏内容块；标题识别启发式（编号章节 `1.`/`1.2`/`(1)`/`一、`/`第X章` + 架构/大纲/目录/知识点/考点/体系 关键词），已收紧规则去掉追问句与冒号短句噪声，避免 outline 块过多稀释常规检索。QA 增强跳过 outline 块（仅对内容块生成问答对）。
> - **检索过滤**：`vector_store` 两种实现的 `search`/`keyword_search` 新增 `chunk_types` 过滤；**常规检索（`chunk_types=None`）默认排除 outline 块**，仅大纲意图显式传 `["outline"]` 才召回，避免稀释常规问答。
> - **大纲意图路由**：`rag.py` 的 `_is_outline_query`（关键词：架构/大纲/目录/有哪些/知识点/考点/体系/模块…）命中时，在检索主流程后追加一轮仅召回 outline 块的「向量 + BM25 融合」，并将这些大纲块**保送结果前部**（跳过 rerank 阈值与相关性门槛过滤，避免短标题块被误剔除），再补常规结果，使 LLM 既能看到知识框架标题又能看到正文细节。新增配置 `retrieval_outline_top_n=12`。
> - **生效条件**：已有文档需重新向量化（重新上传/处理）才会生成 outline 块；新上传文档自动带 outline 块。

> **M5-5 父子分块（parent/child chunk，small-to-big，2026-07-17 已落地）**：对标业界成熟方案——父块入库供上下文召回、子块进向量索引，提升检索精度与上下文连贯度。采用 small-to-big 叠加式（最小侵入现有链路）：
> - **分块**：开启 `enable_parent_child`（默认开）时，`chunk_text` 先按 `parent_chunk_size=1500` 切父块（`chunk_type=parent`/`is_parent=true`），再在父块内用 `chunk_size=512` 切子块并记录 `parent_id` 归属；关闭时退化为原单层分块。父子块与既有 outline 块并行产出、互不破坏。
> - **向量化**：`embed_chunks` 跳过父块（embedding 保持 None，落库 dimension=0 天然不进向量检索），省 Embedding 成本。
> - **入库**：父子块均入 PG（父块带 `chunk_type=parent` 元数据），但 BM25 兜底索引排除父块（过长会干扰关键词召回）。
> - **检索回溯**：`RetrievalResult` 新增 `parent_id`/`parent_content`；`rag.retrieve` 末尾 best-effort 调 `attach_parents`，按 `(doc_id, parent_id)` 批量读回父块内容 small-to-big 填充；`chat.py` 喂 LLM 的上下文优先用 `parent_content`（更连贯），引用来源 `sources` 仍用子块 `content` 精确定位。检索缓存 key 纳入 `retrieval_parent_context`，开关变更自动失效。
> - **增强透传**：`_run_augment_task` 读回父块与子块+qa 增强块一并全量 store（幂等覆盖），避免增强入库时父块丢失。
> - **生效条件**：已有文档需重新向量化（重新上传/处理）才会生成 parent 块；新上传文档自动带父子两层。

> **M5-6 多模态增强（图片 OCR + VLM caption，2026-07-17 已落地）**：对标业界 `image:multimodal`，让文档图片内容可被检索问答（最小侵入现有增强队列与 `chunk_type` 体系，无新基础设施）：
> - **图片抽取**：`extract_images` 按类型委派——PDF 走 `fitz` 逐页 `get_images` 抽原始字节（PyMuPDF 不可用时降级返回空），DOCX 走 `zipfile` 读 `word/media/`，txt/md 不抽图；均按 `multimodal_min_image_bytes` 过滤过小图片（图标/字形噪声）。
> - **OCR + Caption**：复用 M3-3 配置的 LLM（需支持多模态）经 `_ocr_and_caption` 一次多模态调用同时产出 OCR 文字（`chunk_type=ocr`）与图描述（`chunk_type=image_caption`），由 `_parse_ocr_caption_json` 容错解析。
> - **入库与检索**：`_run_augment_task` 在 qa/扩展增强之后调用 `_generate_multimodal_augment`（复用 `task.file_path` 重新抽图，best-effort 跳过失败/缺失）合并全量幂等 store；`AUGMENT_CHUNK_TYPES` 纳入 `ocr`/`image_caption`，默认排除为引用来源（与 qa 同语义），但仍参与向量/BM25 召回，且不污染预览、不触发重建膨胀。
>
> **M5-7 增强内容丰富度扩展（GraphRAG/Auto-Wiki/摘要/推测问题，2026-07-17 已落地）**：在既有问答对(qa)增强基础上，扩展 summary/question/wiki/entity 四类增强块（最小侵入、无新基础设施）：
> - **生成**：新增 `_generate_extended_augment`，以文档拼接文本（截断 `augment_ext_summary_max_chars`）并发生成——`summary` 文档级摘要（覆盖「总结/概述」类问题）、`question` 推测用户检索式问题（桥接口语化问句与文档术语）、`wiki` Auto-Wiki 条目（归纳性知识点）、`entity` 实体关系三元组（GraphRAG，三元组额外存入 `metadata` JSONB 列，不引入独立图数据库，后续可图检索/可视化）。各类 LLM 调用容错解析，配置错误整体放弃扩展增强。
> - **入库**：`_run_augment_task` 在 qa 之后调用扩展生成，四类块与 qa 合并后全量幂等 store；`get_original_chunks` 排除全部增强块，杜绝重建膨胀。
> - **检索行为**：增强块参与向量/BM25 召回提升语义桥接，但默认排除为引用来源（`retrieval_exclude_augment_blocks`，与 qa 同语义）；`get_document_pages`/BM25 warmup 同样排除，避免预览/关键词噪声。
> - **生效条件**：已有文档需重新向量化（重新上传/处理）才会生成扩展增强块；新上传文档自动带 qa + 扩展增强。

> **M5-8 复合检索引擎（pgvector 向量 + Elasticsearch BM25，2026-07-17 方案确认，待实现）**：（ES 一个引擎同时做 kNN + BM25，RAG 检索只做召回+RRF，Rerank 由现有 `rag.py` 阶段负责，PG 保留为分块权威存储），引入 ES 提供工业级 BM25 关键词召回，与向量路做 RRF 融合，并为后续"搜索功能"打下统一 ES 索引基础（复用同一索引，不新增基础设施）：
> - **已确认决策**：① 向量路 = A（零维度风险）——向量召回仍走 **pgvector**（本项目 embedding 变维度，pgvector 原生支持；ES `dense_vector` 维度须固定，故向量不迁 ES），**ES 只负责 BM25 关键词召回 + 未来搜索**；复合检索 = `pgvector 向量` + `ES BM25` → 现有 RRF → Rerank。② 同意新增 `elasticsearch` 官方客户端依赖 + `docker-compose.yml` 的 `xiongda-elasticsearch`（ES 8.x，已注释改用本地 7.17 + 8.x 客户端兼容模式）。
> - **索引设计**：每租户一个索引 `{prefix}_{tenant_id}`（惰性创建，固定 `dense_vector` 维度 + 天然租户隔离）；字段 `content`(text,BM25+高亮) / `embedding`(dense_vector,本期不召回仅备用) / `doc_id` `kb_id` `tenant_id` `chunk_index` `chunk_type` `page` `source`(keyword) / `metadata`(flattened)；检索时 `must_not` 排除 `AUGMENT_CHUNK_TYPES` 增强块。
> - **存储与检索改造**：新增 `ElasticsearchStore`——`store_chunks` 双写 PG+ES（同 doc_id delete-then-insert 幂等）；`keyword_search` 改走 ES BM25（旧自研 BM25 保留为 fallback）；`delete_by_doc` 清 ES；预留 `search_documents`（文档级聚合+高亮，供未来搜索）。`rag.py` 在 `elasticsearch_enabled=True` 时关键词路切 ES，两路送现有 RRF+Rerank；**ES 异常自动降级回旧链路**，不影响问答。
> - **一致性与迁移**：文档删除/增强重建同步调 ES（幂等覆盖，不膨胀）；现有 Redis 三层缓存与 Java 失效逻辑不动；提供 `POST /ai/admin/reindex-es` 存量迁移 + `elasticsearch_auto_reindex_on_empty` 启动补迁。
> - **未来搜索铺垫**：同一 ES 索引直接支撑——按文件名/标题搜（`doc_id` 聚合+highlight，对齐 WeKnora `SearchKnowledge`）与内容搜（chunk 级 BM25+向量，对齐 `hybrid-search`）；后续仅需 Java 转发 + 前端页 + Python `/ai/search` 路由，无需再动索引/存储。
> - **测试**：mock ES 客户端单测（mapping/bulk 幂等/BM25 构造/增强块过滤/delete-by-doc/降级）+ `ES_TEST_URL` 守卫的可选集成测试；旧 pgvector/BM25 测试须全过（无回归）。M5-8 本身 Java、前端零改动。
> - **实现（2026-07-17）**：代码落地于 `ai-service/services/elasticsearch_store.py`（单模块、懒加载；`get_es_store()` 在 `elasticsearch_enabled=False` 时零导入 `elasticsearch` 包，未装依赖不影响旧链路）；`vector_store.PgVectorStore` 在开关开启时双写 / 切 BM25 / 同步删并优雅降级；`routers/admin.py` 加 `POST /ai/admin/reindex-es`；索引 `{prefix}_{tenant_id}`，`dense_vector` 维度由 `embedding_dimension` 驱动（本期 `index:false` 不用于 kNN），BM25 `_score` 归一化（除以批次 max 再乘 `elasticsearch_bm25_scale`）对齐自研 BM25 量级；`docker-compose.yml` 的 `xiongda-elasticsearch`（ES 8.17，已注释改用本地 7.17），`requirements.txt` 加 `elasticsearch==8.17.0`（8.x 客户端兼容模式连 7.x 服务器）。
> - **2026-07-18 部署修正（本地 ES 7.17 + 兼容模式）**：服务端由 docker 拉取 8.17 改为**本地 7.17**（含 IK 中文分词，docker.elastic.co 走代理限速数小时）；客户端保持 8.x 经「兼容模式」header（`elasticsearch_compat_7=True`）连 7.x，规避 `UnsupportedProductError`；`delete_by_doc` 的 `delete_by_query` 改 `refresh=True`（ES 7.x 不支持 `wait_for`）保证删除立即可见；`main.py` 须以 `uvicorn main:app` 启动（无 `__main__` 入口）；实测连接/建索引/3401 块迁移/单测 22 例 + 集成全过。
> - **2026-07-18 本地开发环境启用**：新建 `ai-service/.env`（仅 ES 段：`ELASTICSEARCH_ENABLED=true` / `ELASTICSEARCH_HOSTS=http://localhost:9200` / `ELASTICSEARCH_COMPAT_7=true` / `ELASTICSEARCH_TEXT_ANALYZER=ik_max_word`），重启 Python 服务加载后启动日志出现 `✅ Elasticsearch 检索引擎已就绪`；调 `POST /ai/admin/reindex-es` 全量重建索引（`migrated_chunks=3406`）。自此问答关键词路走 ES BM25 + pgvector 向量 RRF 融合，旧自研 BM25 作为 ES 异常时的降级链路保留。详见 `docs/task-breakdown.md` M5-8。

> **M5-9 多模态问答增强（图片 vision + 附件文本提取，2026-07-18）**：用户在问答输入框可上传图片和通用文档附件，作为本次问答的临时上下文（不入向量库、不跨会话）：
> - **数据模型**：新增 `chat_attachment` 表（`id/tenant_id/user_id/filename/file_type/file_path/file_size/category/create_time`），`category` 区分 `image`（走 LLM vision 多模态调用）和 `attachment`（走文档文本提取拼上下文）。不做逻辑删除（临时文件，可定期清理）。
> - **前端**：`ChatPage.tsx` 输入框新增图片上传按钮（缩略图预览 + 移除）和附件上传按钮（文件名标签 + 移除）；发送时携带 `imageIds/attachmentIds`（string[]）；`AIConfigPage` LLM 模型字段下方加多模态 vision 提示。
> - **Java 透传**：`ChatController.resolveAttachmentPaths(ids, tenantId, category)` 按 ID 查 `chat_attachment` 表（带租户隔离 + 分类校验），解析为文件绝对路径列表；`AiServiceClient.chatStream` 透传 `image_paths/attachment_paths` 给 Python。不存在的 ID 或跨租户记录静默跳过，不阻塞主问答流程。
> - **Python 多模态**：`llm.is_vision_model(model_name)` 白名单判定（gpt-4o/gpt-4o-mini/gpt-4-vision-preview/claude-3-5-sonnet/qwen-vl-max/qwen2.5-vl-72b-instruct/gemini-2.0-flash/glm-4v）；支持 vision 时 `stream_generate_with_images` 把图片转 base64 构造多模态 messages 调 LLM；不支持时抛 `ModelConfigError`。附件文本提取仅在 `mode=rag/web` 时执行（一次性文档问答），复用 `document_processor` 提取 pdf/docx/txt/md 文本拼到 context。
> - **ID 类型**：前端 streamChat 参数用 `string[]`（Jackson Long→String 序列化；`ChatAttachmentVO.id` 改为 string），避免 JS Number 精度丢失。
> - **测试**：Python `test_llm.py` 覆盖 vision 白名单/黑名单、非 vision 模型抛错、不可读图片 fallback、vision messages 构造；Java `ChatControllerTest` 2 例（透传 + 降级），全量 5 例通过。详见 `docs/task-breakdown.md` M5-9。

> **Embedding 并发化（2026-07-16 修复）**：多模态 Embedding 端点（`doubao-embedding-vision` 的 `/embeddings/multimodal`）单次仅支持单条 input，原实现逐条串行，大文档（分块数千）整段向量化可达 10 分钟、且取消无法中断。改为 `asyncio.Semaphore(embedding_concurrency=16)` 限流并发 + 429/5xx 退避重试（详见 task-breakdown M4）。纯文本文档建议用标准 `doubao-embedding`（批量、秒级）。

> **文档处理异步线程池（2026-07-16）**：Java 侧文档处理（`triggerDocumentProcessing`）已由默认 `ForkJoinPool.commonPool()` 改为专用 `ThreadPoolExecutor`（核心2/最大8/队列16，前缀 `doc-process-`，`CallerRunsPolicy` 背压，空闲回收），隔离上传密集 IO 任务、避免挤占公共池。上传请求仍立即返回，进度由前端轮询。

### 5.3 Agent 多步推理（M4-1 + M4-B function calling 升级）

M4-1 采用**自研轻量 ReAct 循环**（非 LangChain AgentExecutor），与业界成熟 RAG 方案 的自研 ReAct 引擎思路一致，基于 LLM 文本协议（Thought / Action / Action Input / Final Answer）。M4-B 升级为 **OpenAI 兼容原生 function calling**，LLM 直接返回结构化 `tool_calls`，解析可靠，同时保留 ReAct 文本降级路径兼容不支持 function calling 的模型。

- **触发方式**：问答页底部「普通问答 / 智能推理」分段切换，前端 `mode=agent` 经 Java 透传至 Python `/ai/chat/stream`（`ChatController` → `AiServiceClient` 早已支持 `mode` 透传，Java 无需改动）。
- **主路径（M4-B function calling）**：`run_agent` 每轮调用 `llm_service.stream_agent_turn(messages, TOOLS)` → 流式 yield 思考文本 token + 流末 tool_calls → 调用 `_execute_tool` 执行工具 → 回填 `assistant(tool_calls)` + `tool` 消息（标准 function-calling 协议），进入下一轮推理，直到模型产出纯文本答案（无 tool_calls）或达到最大轮数（`MAX_AGENT_ITERATIONS=5`）。
  - `llm_service._iter_tokens_with_tools`：按 `index` 累积流式 `delta.tool_calls` 分片，流末一次性 yield 完整调用列表。
  - `TOOLS` 全局定义（OpenAI 兼容格式）追加到请求体 `tools` + `tool_choice: "auto"`。
- **降级路径（M4-1 ReAct 文本）**：本轮 LLM 未返回 tool_calls 时（模型不支持 function calling），用 `parse_react` 解析文本中的 Thought / Action / Action Input / Final Answer → 执行工具 → 回填 Observation 文本，直到 Final Answer。
- **工具统一解析**：`_execute_tool` 通过 `_resolve_query` 兼容 function calling 标准 JSON 与 ReAct Action Input 文本（代码块围栏、多余文字等）。
- **工具范围**（M4-3 已扩展）：`knowledge_base_search`（知识库检索）+ `web_search`（联网搜索，M4-3 新增）。Agent 优先用知识库检索，知识库无结果时可自动调用 `web_search` 从互联网获取信息。联网搜索通过 `services/web_search.py` 的 `web_search(query, max_results)` 实现，基于 httpx + DuckDuckGo Lite HTML 搜索，无需 API Key，返回结构化 `[{title, url, snippet}]`；网络异常时返回空列表、不阻断主流程。搜索结果来源以 `kb_id="web"` 标记、`doc_id=url` 保存链接。
- **Action Input 解析健壮性**：`_extract_query` 兼容 LLM 常见输出形态——标准 JSON、被 ```` ```json ```` 代码块包裹的 JSON、前后混入解释文字、以及纯文本。
- **SSE 事件契约**（M4-B 不变）：`agent_step`（type=thought/action/observation，含 tool/input/success）、`sources`、`token`（最终答案流式）、`error`（MODEL_CONFIG_ERROR / AGENT_ERROR）、`done`。前端 `ChatPage.tsx` 的 `AgentSteps` 组件将推理步骤渲染为可折叠的步骤树。`routers/chat.py` Agent 分支仅透传事件，不感知内部路径切换。

**M4-3 问答模式选择器下拉化（2026-07-18）**：`ChatPage.tsx` 输入框底部的「知识库 / 联网搜索 / 智能推理」三档分段按钮（原 `bg-emerald-50` + `p-0.5` 容器内三按钮横排）占用空间大、模式名长易挤压附件/模型下拉，改为**单下拉框**：新增 `modeMenuOpen` state + `modeMenuRef` ref + 点击外侧自动关闭 effect；触发按钮按当前 `mode` 显示对应图标（书本/地球/闪电）+ 模式名 + 旋转箭头；下拉面板向上弹出（`bottom-full`），三项各带图标 + 名称 + 一句描述（"基于知识库内容回答"/"从互联网搜索最新信息"/"多步推理并自动调用工具"），选中项高亮并打勾。`mode` 状态类型、发送/事件流逻辑、Java/Python 后端均无改动。

**注意（2026-07-18 修复）**：下拉面板用 `absolute bottom-full` 向上弹出，**必须保证面板所在链路无 `overflow:hidden`**。输入框外层容器原本有 `overflow-hidden`（仅为圆角视觉效果，textarea 自身已有 `max-h-[200px] overflow-y-auto`），会裁掉向上弹出的面板，导致只露出最末项的尾巴。已移除 `overflow-hidden`，注释说明「不能用，否则上弹下拉被裁」。

**M4-C 轻量增强**（`services/agent_intelligence.py`，2026-07-14）：
- **memory 固化**：`run_agent` 循环前，若 `history` 长度达标（≥`agent_memory_min_messages`），调用一次 LLM 将多轮历史提取为记忆块（实体/偏好/结论要点），作为 `[对话记忆]` 系统消息注入，使长对话不丢上文。
- **reflection 反思**：每轮工具观察后调用一次轻量 LLM（`reflect`）判断信息充分性；`can_answer=true` 时注入指令引导模型直接产出最终答案，`can_answer=false` 自然继续检索。JSON 解析失败默认继续。
- **上下文压缩**：每轮 LLM 调用前估算 messages 字符数，超 `agent_context_max_chars`（≈6k tokens）时压缩旧轮次 assistant/tool 消息为摘要（保留 system + 用户问题 + 最近 6 条），防超出上下文窗口。
- 三项各自由 `core/config.py` 开关独立控制，异常均有降级策略，配置错误向上透传。事件协议不变，前端/Java 零改动。

复杂问题触发 ReAct Agent：

```
思考(Thought) → 行动(Action) → 观察(Observation) → 思考 → ... → 最终回答

示例：
Q: "我们公司的年假政策是什么？和病假有什么区别？"
Thought: 需要查找年假和病假政策
Action: knowledge_base_search  Action Input: {"query": "年假政策"}
Observation: 找到员工手册第3章...
Action: knowledge_base_search  Action Input: {"query": "病假政策"}
Observation: 找到员工手册第4章...
Thought: 对比两者差异
Final Answer: 年假...病假...区别...
```

---

### 5.4 文档处理深度对标业界成熟方案（M5 规划）

> 目标：把 M4-8.1 的「finalizing 队列」思想扩展到整条主流程，并补齐 业界方案 的健壮性（重试 / 多检查点守卫 / 阶段 span）与功能广度（父子分块 / 多模态 / GraphRAG / 复合检索）。逐项落地前本节仅记录规划，各子任务完成后再补充对应详细设计。
>
> 与 业界的 8 点差异及实现顺序（详见 `docs/task-breakdown.md` M5）：
> 1. M5-1 主流程持久化队列化（解析+向量化入队，对齐 Asynq 整流程异步）
> 2. M5-2 主流程重试机制（MaxRetry + 退避）
> 3. M5-3 多检查点取消守卫（对齐 4 处 isKnowledgeAborted）
> 4. M5-4 阶段化 span 时间线追踪（对齐 beginStage/endStage/failStage）
> 5. M5-5 父子分块（parent/child chunk）✅
> 6. M5-6 多模态增强（图片 OCR + VLM caption）✅
> 7. M5-7 GraphRAG + Auto-Wiki + 摘要/问题 ✅
> 8. M5-8 复合检索引擎（pgvector + 本地 Elasticsearch 7.17 BM25）✅
>
> 实现顺序：M5-1 → M5-2 → M5-3 → M5-4 → M5-5 → M5-6 → M5-7 → M5-8（先主流程健壮性，后可观测性与功能广度，逐个实现）。

## 6. SSE 流式输出设计

### 6.1 接口设计

```
POST /api/chat/message/stream
Authorization: Bearer <token>
Content-Type: application/json

{
  "conversationId": 1234567890,
  "content": "公司年假政策是什么？",
  "kbIds": [111, 222],
  "model": "deepseek-v3.2",
  "mode": "rag"
}
```

> 返回 `Flux<String>` 流，Java **解析 Python 每个 SSE 事件并重新包装为标准 SSE 帧**（`event: token` / `data: {json}`）回传前端，保留 `thinking`/`sources`/`token`/`done` 事件类型，前端按事件名分发。
>
> **注意（已踩坑）**：`produces` 必须为 `MediaType.TEXT_PLAIN_VALUE`。若误用 `TEXT_EVENT_STREAM_VALUE`，Spring MVC 会对 `Flux<String>` 的每个元素自动加 `data: ` 前缀二次包装，破坏手写 `event:` 行（实测出现 `data:event: thinking` / `data:data: {...}` 双包格式，前端无法解析）。前端用 `fetch` + `reader` 手动按行解析，Content-Type 不影响解析。
>
> **修复（2026-07-16，根治）：AI 回复中文乱码 `�`**。初判为 httpx 未设 `response.encoding` 猜测编码，但加 `response.encoding="utf-8"` 重启后仍复现（日志确认乱码问答恰在已含该修复的重启之后）。真正根因：`aiter_lines` 在 LLM 流分块边界把多字节中文字符截断，半截字节被 UTF-8 解码成 U+FFFD。故改用 `aiter_bytes()` 累积原始字节、按 `\n` 切行后整行 `decode("utf-8")`（字符不跨行截断，彻底避免 U+FFFD）；`_iter_tokens` / `_iter_tokens_with_tools` 均改写。文档入库（`_clean_text` 删 U+FFFD）、Java（`ChatController.decode` 用 `StandardCharsets.UTF_8`，WebFlux `StringEncoder` 默认 UTF-8）、前端（`TextDecoder({stream:true})`）均经核查正确。
> **同类修复（2026-07-16）：引用来源中文乱码**。用户复测发现引用来源片段仍有 `�`，但回复正文正确、且 `embeddings` 表全表无 U+FFFD，说明源在 Java 消费 SSE 流这一环：`ChatController.chatStream` 原本对每个 `DataBuffer` 单独 `StandardCharsets.UTF_8.decode(bb)`，HTTP chunk 边界切在多字节中文（如「第」U+7B2C）中间时把半截字节替换成 U+FFFD；token 事件小（单 buffer 完整）故正文无碍，source 大 JSON 跨多 buffer 故偶发。修复：改用流式 `CharsetDecoder` 三参数 `decode(bb, out, false)`（endOfInput=false，跨 buffer 不完整字符缓存待补齐），流末 `decode(empty)`（endOfInput=true）flush；单参数 `decode(ByteBuffer)` 内部 endOfInput=true 会直接 REPLACE 成 U+FFFD，绝不可用。回归测试见 `ChatControllerTest.chatStream_multibyteCharSplitAcrossBuffers_preserved`。

### 6.2 SSE 响应格式

```
event: thinking
data: {"content": "正在检索知识库..."}

event: sources
data: {"sources": [{"filename": "员工手册.pdf", "page": 12}]}

event: token
data: {"content": "根"}

event: token
data: {"content": "据"}

event: done
data: {"conversation_id": "1234567890"}
```

---

## 7. 数据模型总览

### 7.1 核心表

| 表名 | 说明 | MyBatis-Plus 实体 |
|---|---|---|
| `tenant` | 租户表 | Tenant.java |
| `user` | 用户表（含 role 字段） | User.java |
| `knowledge_base` | 知识库表（含 scope 字段） | KnowledgeBase.java |
| `document` | 文档表（含 status 状态机） | Document.java |
| `conversation` | 会话表 | Conversation.java |
| `message` | 消息表（含 sources JSON） | Message.java |
| `ai_config` | AI 模型配置表（LLM/Embedding/Rerank） | AiConfig.java |
| `audit_log` | 审计日志表 | AuditLog.java |
| `chat_attachment` | 问答附件表（图片/文档，临时上下文） | ChatAttachment.java |

> 所有表使用 **BIGINT 雪花ID**（@TableId ASSIGN_ID）、**create_time/update_time 自动填充**（MetaObjectHandler）、**is_delete 逻辑删除**（@TableLogic）。

### 7.2 ER 关系

```
tenants 1──N users
tenants 1──N knowledge_bases
users   1──N knowledge_bases (personal, owner)
knowledge_bases 1──N documents
documents 1──N document_chunks
users   1──N conversations
conversations 1──N messages
tenants 1──N ai_configs
tenants 1──N audit_logs
users   1──N chat_attachments
```

---

## 8. 项目结构

```
multi-rag-employee/
├── backend/                        # Java Spring Boot 3.4 后端
│   ├── pom.xml                     # MyBatis-Plus + JWT + WebFlux + Knife4j
│   ├── src/main/java/com/xiongda/
│   │   ├── MainApplication.java    # @MapperScan @EnableAsync
│   │   ├── annotation/
│   │   │   └── AuthCheck.java      # @AuthCheck(mustRole={}) 权限注解
│   │   ├── aop/
│   │   │   ├── AuthInterceptor.java    # @AuthCheck AOP 切面
│   │   │   └── LogInterceptor.java     # 请求日志 AOP
│   │   ├── common/
│   │   │   ├── BaseResponse.java       # 统一响应 {code, data, message}
│   │   │   ├── ResultUtils.java        # success() / error()
│   │   │   ├── ErrorCode.java          # 错误码枚举
│   │   │   ├── PageRequest.java        # 分页基类
│   │   │   └── DeleteRequest.java      # 通用删除请求
│   │   ├── config/
│   │   │   ├── MyBatisPlusConfig.java  # 分页插件
│   │   │   ├── MyMetaObjectHandler.java # 自动填充 createTime/updateTime
│   │   │   ├── CorsConfig.java         # 跨域
│   │   │   ├── JsonConfig.java         # Long→String 序列化
│   │   │   └── Knife4jConfig.java      # API 文档
│   │   ├── constant/
│   │   │   ├── CommonConstant.java     # 通用常量
│   │   │   └── UserConstant.java       # 用户角色常量
│   │   ├── controller/
│   │   │   ├── HealthController.java
│   │   │   ├── UserController.java     # 登录/注册/成员管理
│   │   │   ├── ChatController.java     # 会话 + SSE 透传 + 附件路径解析
│   │   │   ├── ChatAttachmentController.java  # 问答附件上传
│   │   │   ├── KnowledgeBaseController.java
│   │   │   ├── AiConfigController.java
│   │   │   └── AuditLogController.java
│   │   ├── exception/
│   │   │   ├── BusinessException.java  # 自定义业务异常
│   │   │   ├── ThrowUtils.java         # throwIf() 工具
│   │   │   └── GlobalExceptionHandler.java
│   │   ├── mapper/                     # MyBatis-Plus BaseMapper (9个)
│   │   ├── model/
│   │   │   ├── entity/                 # @TableName 实体 (9个)
│   │   │   ├── enums/                  # UserRoleEnum / DocStatusEnum / KbScopeEnum
│   │   │   ├── vo/                     # 视图对象 (脱敏)
│   │   │   └── dto/                    # 请求对象 (按模块分包)
│   │   ├── service/                    # 接口 + impl/ 实现类
│   │   │   └── impl/
│   │   ├── client/
│   │   │   └── AiServiceClient.java    # WebClient 调 Python AI 服务
│   │   └── utils/
│   │       ├── JwtUtil.java            # JWT 生成/解析
│   │       ├── NetUtils.java           # 获取 IP
│   │       └── SpringContextUtils.java
│   └── src/main/resources/
│       └── application.yml             # MyBatis-Plus + PG + Redis + JWT
├── ai-service/                     # Python AI 服务 (FastAPI + LangChain)
│   ├── main.py
│   ├── requirements.txt
│   ├── core/config.py
│   ├── routers/
│   │   ├── chat.py                 # /ai/chat/stream SSE + 附件文本提取
│   │   ├── document.py             # /ai/document/process
│   │   └── admin.py                # /ai/admin/reindex-es
│   └── services/
│       ├── llm.py                  # LangChain LLM 流式 + 多模态 stream_generate_with_images
│       ├── embedding.py
│       ├── vector_store.py         # 向量存储：PgVectorStore(pgvector 持久化,默认)/ Milvus / 内存
│       ├── rag.py                  # 混合检索 + Rerank
│       ├── document_processor.py
│       ├── process_queue.py        # M5-1 主流程持久化队列
│       ├── augment_queue.py        # M4-8.1 增强队列
│       ├── elasticsearch_store.py  # M5-8 ES BM25 复合检索
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── hooks/
│   │   ├── api/
│   │   └── App.tsx
│   ├── package.json
│   └── tailwind.config.js
├── docker-compose.yml              # PostgreSQL + Redis + Milvus
└── docs/
    ├── requirements.md
    ├── design.md
    ├── ui-design-guide.md
    ├── task-breakdown.md
    └── ui-design/
```

> **模块结构变更（截至 2026-07-13）**：开发期间新增/调整的模块（详见 task-breakdown.md）：
> - Python：新增 `core/redis_client.py`（Redis 异步客户端单例）、`routers/cache.py`（`POST /ai/cache/invalidate` 失效接口）、`services/model_config.py`（`ModelConfigError` 模型配置错误类型，M3-3 取消静默降级）、`services/web_search.py`（联网搜索，M4-3，基于 DuckDuckGo Lite HTML，无需 API Key）。
> - Python（2026-07-14 向量持久化）：新增 `core/pg_client.py`（Postgres 异步连接池单例）；`services/vector_store.py` 新增 `PgVectorStore`（pgvector 持久化，默认启用，重启不丢知识）+ BM25 兜底（启动从 PG 预热）；`main.py` 生命周期建表 + BM25 预热；`routers/document.py` 新增 `DELETE /ai/document/{doc_id}` 清孤立向量（详见 task-breakdown.md M2-8）。
> - Java：新增 `service/KbPermission.java`（RBAC 数据级权限集中规则，M3-1）、`TenantInvitation` 实体与 Mapper + 邀请链路（M3-2）；`AiServiceClient` 增加 `toAiConfigMap` + `invalidateCache`（M3-2/M2-7）；`ChatServiceImpl` 增加 L3 会话缓存读写与失效（M2-7）。
> - 前端：新增 `frontend/src/api/user.ts`、`frontend/src/context/ChatContext.tsx`（会话持久化）、`frontend/src/components/AppLayout.tsx`（历史记录按时间分组 + 7 天以上可折叠）；`MembersPage.tsx` 由静态假数据重写为接真实 API。
> - M3-4 审计日志：Java 新增 `annotation/AuditLog.java`（注解）+ `aop/AuditLogAspect.java`（`@AfterReturning` 切面，自动抓用户/IP/UA 并脱敏记录，埋点于 UserService/DocumentServiceImpl/AiConfigServiceImpl）；`AuditLogService.recordLog` 改 `REQUIRES_NEW` 独立事务并补 `userAgent`；`AuditLogController` 查询补 `userEmail`/时间范围筛选并返回 `Page<AuditLogVO>`；新增 `GET /api/user/logout`（仅记录登出审计）。前端新增 `frontend/src/api/audit.ts`、`pages/AuditLogPage.tsx`（筛选栏 + 表格 + JSON 详情展开 + 分页）。

---

## 9. 功能流程详解

> 本章补全「从用户操作到数据落库」的端到端详细流程，便于通读整个项目。涉及的缓存（L1/L2/L3）详见 §9.5。

### 9.1 端到端主流程（用户视角）

```
① 注册 / 登录
   注册 POST /api/user/register → 自动建租户 + tenant_admin 角色
   登录 POST /api/user/login   → 返回 JWT（前端存 localStorage，后续请求 Authorization: Bearer）
        │
② 知识库与文档
   创建知识库 POST /api/knowledge/add（scope: shared / personal）
   上传文档  POST /api/knowledge/document/upload（MultipartFile；批量上传由前端弹窗选文件入列表、点击「确认上传」后循环调用此接口统一解析）
        → Document 状态机：processing → parsing → retrieving → optimizing(已可检索) → ready / failed（对齐 业界 finalizing（异步增强），前端轮询列表状态）
   批量删除 POST /api/knowledge/document/batch-delete（body: { ids: [] }；fail-fast 全量鉴权后逐个删向量+逻辑删除，只清一次 L1 缓存，返回实际删除数量）
        │
③ 问答
   进入对话页 → 输入问题 → POST /api/chat/message/stream（SSE 流式）
        → 前端流式渲染回答 + 引用来源卡片（SourceCard）
        │
④ 历史会话
   侧边栏 GET /api/chat/conversation/list → 点击某会话 → GET /api/chat/message/list 加载历史
```

### 9.2 文档上传与异步处理全链路

```
前端 multipart 上传
   │
   ▼
Java KnowledgeBaseController.uploadDocument
   → DocumentServiceImpl：保存文件（绝对路径）+ 创建 Document(status=processing)
   │
   ▼  CompletableFuture.runAsync（异步，不阻塞上传响应）
Java DocumentServiceImpl.triggerDocumentProcessing
   → 更新 status=parsing
   → AiServiceClient.processDocument（HTTP POST → Python）
   │
   ▼
Python POST /ai/document/process → process_queue.enqueue（**仅入队，立即返回 {status: processing}**，HTTP 不阻塞）
   │
   └─ 常驻主流程 worker（FastAPI lifespan 启动 run_process_worker）消费队列 → DocumentProcessor.process()
      1) extract_text：PDF(PyMuPDF，C 扩展不可用降级 pdfplumber) / DOCX(python-docx) / MD·TXT(直读)
      2) chunk_text：RecursiveCharacterTextSplitter(512 / 50)
      3) 检索阶段：回调 status=retrieving → embed_chunks：Embedding（命中 L2 缓存跳过 API）→ 向量
      4) 原始块立即 store_chunks 入库（PG 按 doc_id 幂等覆盖）→ 文档【立即可被检索】
      5) 优化阶段：回调 status=optimizing（已可检索，并随回调携带全文 content + chunk_count）
         → 将增强任务**入持久化队列**（Redis）
         （注：process() 在 worker 内执行，无 HTTP 同步返回；HTTP 入队已先行返回 processing）
      │
      └─ 增强常驻 worker（run_augment_worker）消费队列：从向量库读回原始块重建 → 并发生成问答对
         （retrieval augmentation：限并发 qa_concurrency + 批量 Embedding + 单块超时跳过），
         增强块连同原始块全量 store，完成后回调 Java status=ready（看门狗：总超时/异常兜底，
         确保最终推进 ready，不永远卡 optimizing；LLM 配置错误则跳过增强，文档仍 ready；
         任务被取消/文档已删则跳过，不回调 ready）
   │
   ▼
Java triggerDocumentProcessing：发请求后置 status=parsing；仅当 Python 返回**非 processing**（入队失败）
   才落 failed（区分 MODEL_CONFIG_ERROR / MODEL_QUOTA_ERROR）；返回 processing 即视为成功并清该租户
   L1 检索缓存（保证新文档立即可搜）
   → 后续 retrieving/optimizing/ready/failed 全部由 Python worker 经 POST /api/internal/document/status
     回调推进（M5-1 契约变更：状态 + 全文 content 改由回调驱动，不再依赖 HTTP 同步返回）：
     · optimizing 回调携带 content + chunkCount → Java 更新 status + 保存 content（前端仍从 DB 读 content）
     · ready 回调仅推进状态，不覆盖已回填的 content
     · 失败回调：更新 status=failed + errorMsg（+ modelConfigError / quotaError 标记）

> **对齐 业界 finalizing（异步增强）=queryable + 任务队列**：向量化完成即可检索，optimizing 仅表示问答增强
> 后台进行中，不再阻塞用户检索（旧版串行等待增强，大文档会卡在「优化中」约 20 分钟）。增强任务改为
> **持久化队列**（`services/augment_queue.py`，Redis list `xiongda:augment:queue`，进程内 asyncio 任务
> 改为跨重启不丢）：`process()` 向量化后仅 store 原始块 + 回调 optimizing + 入队即返回；常驻 worker
> （`document_processor.run_augment_worker`，FastAPI lifespan 启动）消费队列，从向量库读回原始块重建
> 并生成问答增强块全量 store，完成后回调 ready。阶段状态经 POST /api/internal/document/status 实时回调
> （retrieving / optimizing / ready），best-effort 失败仅告警不阻塞。
>
> **崩溃恢复（sweep）**：队列采用 queue → processing 两段式（RPOPLPUSH，processing 记录带 started_at），
> 启动时 `sweep_stale` 把卡死（处理中超时）的任务移回 queue，服务重启不丢增强任务。
> **任务取消**：Java 删除文档时调 Python `DELETE /ai/document/{doc_id}`，清向量库并 `mark_cancelled(doc_id)`，
> worker 取任务前检查 cancelled 集 / 原始块是否已不存在，命中则跳过（不回调 ready），对标业界成熟方案 任务取消。
> Java `updateDocumentStatus` 带「终态守卫」：ready/failed 落定后忽略迟到的最终之前阶段回调，防止竞态回退。
```

> 文档删除（POST /api/knowledge/document/delete，逻辑删除）同样触发 `invalidateCache`，保证下次提问回源重新检索。

> **文档批量删除（2026-07-16）**：`POST /api/knowledge/document/batch-delete`（body `{ ids: [] }`）→ `DocumentService.deleteDocuments` 先对全部文档 fail-fast 校验（存在 / 租户隔离 / 知识库写权限），任一不通过即抛异常、不删除任何文档；全部通过后逐个调 Python 清向量 + 逻辑删除，最后**只清一次**该租户 `retrieval:{tenant}:*` L1 缓存并统一同步涉及知识库的文档数（较单删循环 N 次清缓存更高效），返回实际删除数量。批量上传复用单文件上传接口，由前端循环调用，后端零改动。详见 `docs/task-breakdown.md` 对应小节。

> **知识库批量上传/删除改为弹窗表单（2026-07-16 前端）**：`KnowledgeBasePage` 不再在文档列表旁用行内勾选，顶部工具栏改为「新建知识库 / 批量上传 / 批量删除」三个独立按钮。**批量上传**：点击弹出模态框，用户从本地选择文件或拖拽文件进入列表（可多次添加），**选完不立即解析**；列表中可单条移除；点击「确认上传」后才统一循环调用 `uploadDocument` 走完整上传+解析流程（含逐个进度、失败汇总）。**批量删除**：点击弹出模态框，展示当前知识库文档列表（带全选/取消全选），勾选后点击「确认删除」统一调 `batch-delete`。后端接口零改动，仅前端交互形态变更。详见 `docs/task-breakdown.md` 对应小节。

> **文档取消（软取消，2026-07-15 落地，M5-3 补全检查点）**：用户可在处理中任意阶段（processing/parsing/retrieving/optimizing）点「取消」停止。`POST /api/knowledge/document/cancel` → Java 标 `cancelled`（终态，终态守卫阻止中间态回退）→ `POST /ai/document/{doc_id}/cancel` 清向量 + `mark_cancelled`；Python `process()` 在主流程 4 处检查点检查 `process_queue.is_cancelled`（统一复用 `xiongda:doc:cancelled` set，对齐 M5-1 主流程队列语义）：① worker 取任务前（翻 processing 前）② 嵌入前（写 chunk 前）③ 入库前（索引前，M5-3 新增，Embedding 完成后 store 前，未写向量无需清理）④ 入库后、回调 optimizing 前（已写向量必须清理 + 回调 cancelled）。命中即清向量 + 回调 `cancelled` 并不返回 optimizing。竞态：若取消发生在 Python 跑完返回 ready/optimizing 之后，因 DB 已 cancelled，触发分支主动清向量，避免残留可检索但已取消的文档。详见 `docs/task-breakdown.md` M5-3。

> **文档处理失败重试（2026-07-16）**：`failed`/`cancelled` 终态文档在操作列显示「重试」。`POST /api/knowledge/document/retry` → Java 校验权限 + 仅终态可重试 + 校验原文件仍存在 → 绕过终态守卫直接重置为 `processing` 并清空 `errorMsg`/`modelConfigError`/`chunkCount` → 复用原上传者 AI 配置重新触发处理。附带修复两处：① `AiServiceClient` 的 WebClient 响应缓冲上限从默认 256KB 放宽到 20MB（`maxInMemorySize`），修复大文档（如 394 页 PDF 全文随响应返回）触发 `DataBufferLimitException`；② `updateDocumentStatus` 成功终态（ready/optimizing）强制清空 `errorMsg`/`modelConfigError`，且因 MyBatis-Plus `updateById` 默认 `NOT_NULL` 策略忽略 null 字段，给 `Document.errorMsg`/`modelConfigError` 加 `@TableField(updateStrategy=ALWAYS)` 使置 null 能写库，避免重试成功后旧错误信息残留。详见 `docs/task-breakdown.md` 对应小节。

> **M5-1 主流程持久化队列化（2026-07-17，已落地）**：把「提取→分块→向量化→入库」整段主流程从 Python 同步 HTTP handler 搬进持久化任务队列 worker（对齐业界成熟方案 把 `ProcessDocument` 整体跑在 Asynq 持久化任务里）。`POST /ai/document/process` 仅 `process_queue.enqueue`（Redis list `xiongda:doc:queue`，queue→processing 两段式带 `started_at`，`xiongda:doc:cancelled` 取消集），立即返回 `{status: processing}`；常驻 `run_process_worker`（FastAPI lifespan 启动）消费队列执行 `process()`，摄取异常经回调推 `failed`（区分 `model_config_error`）。删除/取消接口同步 `process_queue.mark_cancelled`，worker 取任务前跳过。启动时 `sweep_stale`（`sweep_process_stale`）恢复卡死主流程任务，服务重启不丢。阶段状态（retrieving/optimizing/ready/failed）及全文 `content` 完全由 worker 经 `POST /api/internal/document/status` 回调推进（Java `updateDocumentStatus` 新增 7 参重载携带 content），HTTP 同步返回不再承载状态/全文。详见 `docs/task-breakdown.md` M5-1。

> **M5-2 主流程重试机制（2026-07-17，已落地）**：在 M5-1 队列基础上叠加失败重试（对标 Asynq `MaxRetry(3)` + 退避）。任务新增 `retry` 字段；`run_process_worker` 每轮先 `promote_delayed()` 把到期的延迟重试任务搬回主队列。重试分流（`_run_process_task`）：`ModelConfigError`（密钥/模型名/维度）**不可重试**，直接回调 `failed` + `model_config_error=True`；其余瞬时异常（网络/`ModelQuotaError` 限流/DB 瞬时错误）按 `retry` 计数经 `requeue_delayed` 写入延迟 zset `xiongda:doc:delayed`（score = `next_attempt_at = now + backoff_delay(attempt)`，指数退避 30/60/120s 封顶 600s），到期后由 worker 重新执行；达 `MAX_RETRY=3` 上限回调 `failed`（error_msg 含「已重试 N 次」）。PG `store_chunks` 先删后插保证重试幂等、不重复分块；延迟任务持久化于 zset，服务重启后到期即恢复。详见 `docs/task-breakdown.md` M5-2。

> **M5 规划**：M5-1 ~ M5-7 已完成；M5-8 复合检索引擎（本地 Elasticsearch 7.17 BM25 + pgvector 向量 RRF 融合）**已完成（2026-07-17 实现，2026-07-18 切本地 7.17 兼容模式）**（见 `docs/task-breakdown.md` M5-8）。

> **M5-4 阶段化 span 时间线追踪（2026-07-17 已落地）**：文档处理全程拆成带时间线与指标的阶段——解析(parsing) → 分块(chunking) → 向量化(embedding) → 入库(indexing) → 增强(optimizing)。Python 经 `_StageTimer` 上下文管理器在每阶段边界回调 `notify_stage`（best-effort POST `/api/internal/document/stage`，携带 `startedAt/endedAt/elapsedMs/error/metrics`），Java `recordDocumentStage` 按 `stage` 幂等合并写入文档 `process_stages`（TEXT JSON 数组）；前端 `KnowledgeBasePage` 状态单元格下以 `StageTimeline` 展示阶段链（done 绿✓+耗时 / failed 红✕ / active 旋转符），便于细粒度进度观察与失败定位。详见 `docs/task-breakdown.md` M5-4。

> **前端状态轮询修复（2026-07-18）**：`KnowledgeBasePage` 列表状态轮询原仅在 `processing/parsing/retrieving/optimizing` 时每 3 秒刷新；一旦状态变为终态 `failed` 即停止轮询，导致后端把 `failed` 改写成 `ready`（重试成功 / 自动恢复）时前端无法自动感知，需手动刷新。修复后轮询条件扩展为「处理中四态 **或** 可自动恢复的 `failed`」——即 `status=failed` 且非 `modelConfigError` 且非 `quotaError`（这两类为须人工干预的终态，不轮询）。对可恢复 `failed` 的轮询加 5 分钟窗口上限（`FAILED_POLL_MAX_MS`），避免永久失败文档空转；恢复为 `ready` 或转回处理中后清空计时标记。详见 `docs/task-breakdown.md` 对应小节。

> **排查闭环（2026-07-18）：文档「处理失败」= 后端陈旧构建误判**：当天 `Git工作中需要的操作.docx` 两次显示「处理失败」。第一次（13:38）为上述前端轮询缺陷（已修）；第二次（14:01:47）根因是**运行中的 Java 后端为 M5-1 之前的陈旧 jar**（PID 23004 启动 7/17 12:51，早于 `DocumentServiceImpl.java` 源码 7/17 21:36 修改）——旧逻辑把 Python 入队返回的 `status=processing` 误判进失败分支置 `failed` + `未知错误`，其后的 `retrieving/optimizing/ready` 回调被终态守卫拒绝。重启后端（PID 37668，加载 7/17 21:36 源码）后，该文档已由 Python worker 回调 `ready` 自动修复。结论：本次非代码 bug，而是「源码改了但运行实例未重启」导致的陈旧构建问题，重启即恢复。详见 `docs/task-breakdown.md` §23。

### 9.3 RAG 问答全链路（含三层缓存）

```
前端 POST /api/chat/message/stream（body: conversationId / content / kbIds / model / mode / history）
   │
   ▼
Java ChatController.chatStream
   1) conversationId == null → 自动 createConversation（标题取问题前 50 字），id 用 String 避免 JS 大整数精度丢失
   2) saveUserMessage(convId, content)
        → 先写 Redis L3（chat:conv:{id}，TTL 1800s）再落库 message 表
   3) 调用 Python /ai/chat/stream（WebClient → Flux<DataBuffer>），Java 侧将原始 SSE 按 `\n\n` 切帧，逐帧转译为标准 SSE（`event:/data:`）推给前端；
      ⚠️ produces 用 TEXT_PLAIN 避免 Spring 对 Flux<String> 二次加 data: 前缀
   │
   ▼
Python event_generator（routers/chat.py）
   ├─ 发 event: thinking
   ├─ mode == "web"（M4-3）→ web_search(query, max_results) 联网搜索
   │     ├─ 基于 httpx + DuckDuckGo Lite HTML，无需 API Key
   │     ├─ 有结果 → 格式化为 LLM 可读上下文 + sources（kb_id=web, doc_id=url）
   │     └─ 网络异常/无结果 → 降级无上下文问答（走 fixed/model 兜底）
   ├─ mode == "rag" → rag_service.retrieve(question, kb_ids, tenant_id)
   │     ├─【L1 检索缓存】命中 retrieval:{tenant}:{q_hash} → 直接返回，跳过向量/BM25/RRF/Rerank
   │     └─ 未命中：
   │           ├─【L2 嵌入缓存】embedding:{text}:{model} 命中 → 复用 query 向量，跳过 Embedding API
   │           ├─ 向量检索 Top-K=20 + BM25 关键词检索 Top-K=20
   │           ├─ 向量主导融合（语义优先，BM25 仅补充不足）
   │           ├─ LLM 重排 Top-N（用已配置 LLM 打 0~1 相关性分并重排，按阈值过滤跨主题块）
   │           └─ 写 L1 缓存（TTL 3600s，key 含 rerank 方法，切换算法自动失效）
   │     检索失败 → 降级无上下文问答
   ├─ 有 sources → 发 event: sources（引用来源：文件名 / 页码 / 内容片段）
   ├─ 检索无结果兜底（见下「检索无结果兜底策略」）
   ├─ LLM stream_generate（携带 history 多轮上下文）→ 逐 token 发 event: token
   └─ 发 event: done（conversation_id, sources）
```

> **检索无结果兜底策略（对标业界成熟方案，2026-07-14）**：知识库/文档为空或检索不匹配时不再让 LLM 静默凭通用知识作答，而是走可配置兜底（`core/config.py` 的 `fallback_strategy`，默认 `model`）：
> - **rag 普通问答**：
>   - `fixed`：检索无结果直接发 `event: token`（内容=`fallback_response` 固定文案「知识库暂无相关内容…」），**不调用 LLM**（省成本），随后 `done`。
>   - `model`（默认）：仍调用 `llm.stream_generate`，但传入 `no_kb_content=True`，在 system 指令中要求 LLM 用通用知识兜底并**明确声明「知识库中暂无相关内容，以下回答基于通用知识，仅供参考」、严禁编造或声称内容来自知识库**。
> - **Agent 智能推理**：`rag` 之外的 `agent` 模式，`_execute_tool` 的 `knowledge_base_search` 检索空结果时，返回 Observation「在知识库中未找到相关内容（已检索 N 个知识库）」并附约束「不要使用训练数据/通用知识编造」「严禁编造或虚构来源」；Agent 系统提示同步要求「检索明确返回未找到相关内容时如实告知、不得编造」。前端无需改动（兜底文本以 `token`/`observation` 事件正常渲染）。

> **助手回答持久化（已修复）**：Java 在透传 SSE 流时聚合 `token` 事件累积回答文本、捕获 `done` 事件的 `sources`，流结束后调用 `saveAssistantMessage`（先写 L3 缓存再落库），刷新后历史可完整回看。
> **SSE 封装（已修复）**：Java 不再裸透传 Python 的 `data:` 文本碎片，而是解析每帧后重新包装成带 `event:` 的标准 SSE 发给前端（保留事件类型），前端 `ChatPage.tsx` 按 `event` 名分发（token 追加 / sources 渲染卡片 / done 结束并刷新）。**落库动作从响应式 Netty 线程 offload 到 `Schedulers.boundedElastic()` 并加 try-catch，避免阻塞 I/O 线程且异常不被静默吞掉。**
> **前端停止生成（已修复）**：流式未正常结束（如 LLM 响应极慢、HMR 热更新保留状态）会让 `streaming` 卡在 `true`，导致输入框 `disabled` 无法输入。改用 `AbortController`：`streamChat` 透传 `signal`，生成中显示「停止」按钮可主动中断（`AbortError` 不报错、保留已生成内容）；切换会话时 `useEffect` 自动 `abort()` 旧流并重置 `streaming`，恢复输入态。
> **未配置模型常驻提示（M3-3 前端）**：进入问答页即调用 `GET /api/ai-config/`，按「provider 与 model 均非空」判定 LLM / Embedding 是否已配置（API Key 不在前端可见范围，故不纳入判定）；任一未配置则在消息区顶部渲染常驻琥珀色横幅并列出缺失项（仅 LLM / 仅 Embedding / 两者皆缺），附「去配置」跳转到 `/ai-config`。接口异常时静默忽略，不打扰对话。
> **模型配置正确性运行时检测（M3-3 已实现）**：上述常驻提示只覆盖"字段是否为空"。M3-3 额外覆盖**配置填了但填错**的情况——API Key 错误 / 模型名错误 / 提供商不匹配 / 向量维度不匹配，会导致上传文档向量化失败或对话 LLM 调用失败。落地：① Python 新增 `ModelConfigError` 并真正消费用户配置、**取消静默降级**（无 Key 直接抛错而非造假向量/假回答），调用失败 / 维度不匹配抛此错误；② Java `AiServiceClient.toAiConfigMap` 把配置以 `ai_config` 透传 Python，失败时解析 `error_type==MODEL_CONFIG_ERROR` 置 `document.modelConfigError`；③ 前端 `ChatPage` 解析 SSE `event: error` 的 `MODEL_CONFIG_ERROR` 渲染红色「模型配置不正确」横幅 + 跳转，`KnowledgeBasePage` 在文档 `modelConfigError` 时同样提示。与存在性常驻横幅构成"存在性+正确性"两层提示。
> **Embedding 向量维度：必填 + 按提供商白名单透传（2026-07-17 调整）**：向量维度由"可选、标准路径不传"改为**必填**，且**按提供商白名单决定是否透传 `dimensions`**。① **必填（前端体验与普通字段一致）**：`AIConfigPage` 的「向量维度」字段**不显示红色必填星号/红框、选厂商时也不自动填充**（与「模型名称」等普通字段一致）；保存时若已配置 Embedding 但维度非正整数，则在维度框**下方内联**提示「请填写向量维度（正整数）」并拦截提交；Java `AiConfigServiceImpl.applyUpdate` 对 `embeddingProvider/model` 非空但 `embeddingDimension` 为 null/≤0 抛 `IllegalArgumentException`；Python `_embed_remote` 在 `embedding_dimension` 为空时直接抛 `ModelConfigError`（维度必填仍保留，用于结尾维度一致性校验）；② **按白名单透传**：`embedding.py` 顶部定义 `DIMENSION_SUPPORTED_PROVIDERS = {"火山方舟", "OpenAI", "阿里云百炼"}`，仅当 `cfg.embedding_provider` 在白名单内才发送 `dimensions=用户填写值`，白名单外的提供商（如 `BGE` 的 bge-m3）不识别该参数（传入会 400）故**不传**、使用模型默认维度；L2 缓存 key 已纳入维度避免不同维度串缓存。这样百炼 / 火山方舟 / OpenAI 严格按用户填的维度向量化，而 BGE 等走原生维度。
> **AI 配置保存「删除即清空、不自动回填」（2026-07-17 修复）**：此前 `AiConfigServiceImpl.applyUpdate` 对所有非密钥字段用 `if (StringUtils.isNotBlank(...))` 守卫——**空白时不更新、保留旧值**，导致前端删掉字段（如模型名称）后点保存，后端保留旧值并在返回 `updated` 中带回，前端 `setForm(toForm(updated))` 又把旧值填回，用户误以为"自动填写"；同时 LLM 主模型空但「更多模型」列表非空时会自动取列表第一项填充主模型。修复：① 非密钥字段（provider / model / baseUrl / temperature / maxTokens / embedding* / rerank* / dimension）改为**空白即清空**——新增 `toBlankable(String)` 将 null 或全空白归一为 `null` 后 `set`，删除字段保存后即为空白、不再回填；② **移除** `llmModel = llmModels.get(0)` 的自动填充，主模型留空就留空；③ **仅 API Key 保留「留空不修改」语义**（避免误覆盖已存密钥，前端占位提示亦如此）。前端无需改动——后端现在返回空白值，表单自然显示空白。单测 `AiConfigServiceImplTest` 新增 `updateConfig_blankFieldClearsStoredValue`（空白清空旧值）、并将 `updateConfig_persistsLlmModelsJsonAndFillsDefaultModel` 断言改为「主模型留空不再自动取第一项」（仍验证 llmModels 列表持久化），**10 例全过**。
> **AI 配置模型必填校验 + 删除 Rerank（2026-07-17 再调整）**：① **模型必填拦截**：在「删除即清空」之上新增**保存拦截**——`AiConfigServiceImpl.applyUpdate` 在 `llmProvider`（或 `embeddingProvider`）非空但对应 `model` 为空时抛 `IllegalArgumentException("LLM/Embedding 模型名称为必填项，请在 AI 配置页填写…")`，拒绝保存；前端 `handleSave` 增加预校验，provider 已选但模型名空白时直接在顶部红字提示「请填写 LLM/Embedding 模型名称」并阻止提交（与后端拦截一致）。这样「删掉模型名」既不会自动回填、也不会静默保存成功，而是明确引导用户补全。② **删除 Rerank 配置区块**：AI 模型配置页移除「Rerank 重排序模型」卡片、`RERANK_ICON` 常量与状态概览中的 Rerank 项（仅前端页面，未动后端 `ai_config` 表与 `toVO` 字段）。单测 `AiConfigServiceImplTest` 新增 `updateConfig_missingLlmModelWhenProviderSet_throws` / `updateConfig_missingEmbeddingModelWhenProviderSet_throws`（provider 已填、模型空白抛异常），并将 `updateConfig_persistsLlmModelsJsonAndFillsDefaultModel` 改为显式设 `llmModel`（证明主模型以显式填写为准、不取列表首项），`updateConfig_blankFieldClearsStoredValue` 去掉 provider 以免触发必填；**12 例全过（BUILD SUCCESS）**；重启 Java（PID 28352）Tomcat on 8080 Started。
> **模型配置错误的前端快速恢复（2026-07-14 修复）**：对话 LLM 调用失败触发 `MODEL_CONFIG_ERROR` 时，前端 `ChatPage` 收到 `event: error` 立即 `break` 结束流式，使输入框恢复、不再卡在"思考中"；同时 Python `llm.py` 将 httpx 连接超时收紧为 10s、`base_url` 为空时立即抛错，避免请求悬挂导致前端长时间等待。`routers/chat.py` 在检索阶段命中 `ModelConfigError` 时补发 `done` 并结束，避免重复 `event: error`。

> **额度/限流错误与配置错误分离（2026-07-17 修复）**：此前 Embedding/LLM 调用遇到 HTTP 429（额度耗尽/限流）或 5xx（服务过载）时被**统一转成 `ModelConfigError`**，前端误报"模型配置不正确，请重新配置"，诱导用户去改配置——而真实原因是 Qwen 等模型额度减少/被限流。修复：① Python 新增 `ModelQuotaError`（额度/限流错误类型）与 `is_quota_error()` 关键字判定（quota/额度/余额/insufficient/rate limit/429/too many requests/balance…）；`embedding.py` 429/5xx 重试耗尽、或 4xx 响应体含上述关键字时抛 `ModelQuotaError`，其余 4xx（Key/模型名错）仍为 `ModelConfigError`；`llm.py` 在 `_assert_usable_response` 中套用同规则；② 路由层 `routers/document.py` 返回 `error_type: MODEL_QUOTA_ERROR`、`routers/chat.py` 产出 SSE `error_type: QUOTA_ERROR`；③ Java `Document`/`DocumentVO` 新增 `quota_error` 列（`schema.sql` 已 `ALTER TABLE` 上线），`DocumentServiceImpl.triggerDocumentProcessing` 解析 `error_type==MODEL_QUOTA_ERROR` 置位、`updateDocumentStatus` 6 参重载透传、`getDocumentVO`/`retryDocument` 同步清位；④ 前端 `KnowledgeBasePage` 在文档 `quotaError` 时渲染**琥珀色**"额度不足/被限流，请稍后重试"横幅 + 「重试」按钮（区别于红色"去配置"配置错误横幅），`ChatPage` SSE `QUOTA_ERROR` 同样渲染琥珀色横幅。与红色配置错误提示构成"配置错误→去改配置 / 额度限流→稍后重试"的精准分流。

> **批量 Embedding 超限（batch size > 20）修复（2026-07-17）**：`EmbeddingService._embed_remote` 标准路径此前将整篇文档全部 chunk 一次性作为 `input` 发给 Embedding API，超出阿里云百炼等接口的每批 ≤ 20 条硬限制而报 `HTTP 400 batch size is invalid`；且原代码把任意 4xx 笼统归为 `ModelConfigError`，误导用户去改配置。修复为按 `EMBEDDING_BATCH_SIZE = 20` 分批调用再按序合并，对所有 OpenAI 兼容提供商均安全。详见 task-breakdown.md M3-3。

> **重新配置保存后清除旧归因标记（2026-07-17 修复）**：知识库页红色「模型配置不正确」横幅由 `documents.some(d => d.modelConfigError)` 驱动。历史失败文档的 `model_config_error` 标记仅在成功终态、`retryDocument`、或命中 `MODEL_QUOTA_ERROR` 时清位，用户重新保存一份（可能）正确的配置后该标记仍残留，横幅持续误导"配置仍错"。修复：`AiConfigServiceImpl.updateConfig` 保存成功后调 `documentService.clearFailedConfigErrorFlags(tenantId)`，平台级 `updatePlatformDefault` 成功后代 `null` 清全库；`DocumentServiceImpl.clearFailedConfigErrorFlags` 用 `UpdateWrapper` 仅对 `status='failed'` 且 `model_config_error/quota_error` 为 true 的记录清零（文档保持 `failed` 终态，需用户手动重试按新配置重新归因）。前端逻辑无需改动——后端清位后下次刷新即消失。

> **AI 配置保存成功后按来源跳回（2026-07-17 调整）**：原 `AIConfigPage` 保存成功后无条件 `navigate('/chat')`，导致从知识库页（文档向量化失败提示）点「去配置」改完配置、保存后却被带离知识库。调整：各「去配置」入口带 `from` 参数——`KnowledgeBasePage` 传 `?from=/knowledge`、`ChatPage` 两处（运行时错误红横幅 / 未配置常驻琥珀横幅）传 `?from=/chat`；`AIConfigPage` 用 `useSearchParams` 读取 `from`，保存成功后跳回该来源（无 `from` 默认 `/chat`）。纯前端改动，后端无需变。

> **循环依赖致后端启动失败（2026-07-17 回归修复）**：M3-3 清除旧归因标记改动在 `AiConfigServiceImpl` 注入 `DocumentService`，与 `DocumentServiceImpl` 注入的 `AiConfigService` 形成互依环，使 `mvn spring-boot:run` 报 bean 循环依赖、BUILD FAILURE、8080 不监听。修复：对 `AiConfigServiceImpl.documentService` 加 `@Lazy` 延迟注入（调用点均在配置保存运行时，非启动路径），不动公开接口。单测因 Mockito 未能暴露该回归。

### 9.4 SSE 事件流协议

实际事件类型（见 `routers/chat.py` `_sse`）：`thinking` → `sources`（可选）→ `token`（多个）→ `done`。

```
event: thinking
data: {"content": "正在思考..."}

event: sources
data: {"sources": [{"source": "员工手册.pdf", "page": 12, "content": "年假...", "score": 0.83, ...}]}

event: token
data: {"content": "根"}

event: token
data: {"content": "据"}

event: done
data: {"conversation_id": "1234567890", "sources": [...]}
```

> 前端按 `event` 类型分发：`sources` → 渲染引用来源卡片（web 模式新增 `filename=网页标题` / `doc_id=url` / `kb_id="web"`）；`token` → 追加到当前回答；`done` → 结束流式并刷新会话列表。

### 9.5 三层缓存架构与失效

| 层 | key | 位置 | 缓存内容 | TTL | 命中后跳过 | 失效时机 |
|---|---|---|---|---|---|---|
| L1 检索结果 | `retrieval:{tenant_id}:{q_hash}` | Python `rag.py` | 混合检索+Rerank 结果 | 3600s | 向量/BM25/RRF/Rerank | 文档 ready / 删除 → `invalidateCache` 清 `retrieval:{tenant}:*` |
| L2 嵌入向量 | `embedding:{text_hash}:{model}` | Python `embedding.py` | 文本向量 | 86400s | Embedding API | 长期有效，模型不变即复用 |
| L3 会话状态 | `chat:conv:{conv_id}`（Redis List） | Java `ChatServiceImpl` | 最近 50 条消息 | 1800s | DB 历史查询 | `deleteConversation` 清 `chat:conv:{id}` |

- **L1 跨会话 tenant 级**：同租户任何人问相同问题（含知识库范围）命中，跳过检索阶段；答案仍由 LLM 实时生成（不过时）。
- **L3 先写 Redis 再落库**：`saveUserMessage/saveAssistantMessage` 先 `rightPush` 到 `chat:conv:{id}` 并刷新 TTL，再 `messageMapper.insert`；`listMessages` 先查 Redis，命中即返回跳过 DB，未命中回源 DB 并回填。
- **失效接口**：`POST /ai/cache/invalidate`，body `{ "tenant_id": "...", "scope": "retrieval" }`，清该租户 `retrieval:{tenant}:*`。

### 9.6 会话管理与前端持久化

```
会话生命周期
   ├─ 新建对话按钮：仅 setActiveId(null) 切到空白窗口，不调后端（不落库、无空会话副作用；连点无副作用）
   ├─ 发首条消息：conversationId==null → Java 自动 createConversation 落库 → 出现在历史栏
   └─ 删除会话：POST /api/chat/conversation/delete（校验归属，删会话+消息+清 L3 缓存）

前端持久化（ChatContext）
   ├─ activeId 初始化读 localStorage['xiongda_active_conversation'] → 刷新后恢复上次会话并加载历史
   ├─ setActiveId 同步写入/清除该 key
   └─ refresh() 列表加载后清理已被删除的 activeId

消息状态提升（跨路由切换保留，2026-07-16 修复「切走对话页消息丢失」）
   ├─ messages 不再存于 ChatPage 本地 useState，改为 ChatContext 按会话缓存 messagesByConv[convId]
   ├─ 新会话流式 done 注册前用占位键 PENDING_KEY='__pending__' 暂存进行中消息，done 后迁移到真实 convId 并标记已加载
   ├─ loadedConvIds 记录已从后端加载过历史的会话；路由重挂时若已加载则跳过后端重载，避免覆盖进行中的流式回复
   ├─ streaming 提升为 ChatContext.isStreaming（全局单一），由 sendMessage 的 finally 在流真正结束/中止时统一置否；切走后流在后台继续写入缓存、切回仍可见且输入框保持禁用（2026-07-16 修复：曾因重挂 effect 误置 false 导致切回瞬间空白，已移除该误置并对 PENDING/已加载分支置 followRef+atBottomRef 跳到底部跟随 token）
   └─ 新建对话 setActiveId(null) 仅清空 PENDING 进行中消息，区别于路由切走（后者不触发、保留消息）

历史加载（L3）
   listMessages(conversationId)
     ├─ Redis 命中 chat:conv:{id} → 直接返回（跳过 DB）
     └─ 未命中 → 查 DB → 回填 Redis 后返回
```

### 9.7 跨服务调用一览

| 调用方 | 目标 | 接口 | 方式 |
|---|---|---|---|
| 前端 | Java | `/api/chat/message/stream` | SSE（port 5173 → 8080） |
| 前端 | Java | 会话/知识库/用户 CRUD | REST + JWT |
| 前端 | Java | `/api/knowledge/document/file/{docId}` | HTTP 文件流（M4-4 预览，iframe） |
| 前端 | Java | `/api/knowledge/document/file/status/{docId}` | 预览前置校验：检测原文件是否被删 / 被改，返回 `{exists, changed, message}`（避免 Whitelabel 错误页） |
| 前端 | Java | `/api/knowledge/document/pages?docId=` | 真实分页预览（M4-4 增强）：返回 `[{pageNo, text}]`，PDF 真实页码 / docx-txt-md 估算页码，与引用来源一致；三级兜底——① AI 解析原文件 `extract-pages` → ② 向量库已存分块重建 `pages-from-db`（2026-07-16 新增，原文件被清理/中文路径失败仍可用）→ ③ AI 不可用时已存全文估算 |
| Java | Python | `/ai/chat/stream` | HTTP SSE 透传（WebClient） |
| Java | Python | `/ai/document/process` | HTTP（异步触发） |
| Java | Python | `/ai/cache/invalidate` | HTTP（文档变更清 L1） |
| Python | 外部 AI | `/embeddings`、`/chat/completions`、`/rerank` | OpenAI 兼容（可选，未配则降级） |

---

## 10. 检索增强（M6，参考腾讯 WeKnora）

> 详细方案设计见 `docs/design-search-enhancement.md`

### 10.1 RRF 参数可配置化（M6-1）

将融合参数（RRF k、向量/关键词权重、相关性门槛等）从 `config.py` 硬编码提升为**租户级配置**，存 `tenant.retrieval_config` JSONB 字段，有默认值兜底。Java 透传给 Python，Python 在检索时读取覆盖 `settings` 默认值。前端 AIConfigPage 新增「检索配置」卡片。

### 10.2 Chunk 文本匹配拼接（M6-2）

纯函数模块 `chunk_merge.py`，按文本后缀匹配去除相邻块间的重叠，不依赖位置坐标（StartAt/EndAt），能处理补写表头/HTML 实体导致的位置偏差。集成在 `attach_parents` 之后，对同一文档相邻 chunk_index 的命中块自动拼接。

### 10.3 FAQ 负向问题过滤（M6-3）

FAQ chunk 新增 `negative_questions` 元数据（JSONB metadata 已支持，不需改表）。QA 增强时 LLM 生成负向问题写入 metadata。检索后精确匹配：用户 query 完全等于负向问题 → 剔除该 chunk。

### 10.4 相邻块补全（M6-4）

检索命中某块后，按 `doc_id + chunk_index±1` 查询同文档的前/后一块，作为上下文补全注入 LLM。补全块标记 `is_context_expansion=True`，不作为引用来源展示。与父子分块（small-to-big）互补：父子分块提供更大范围上下文，相邻块补全提供精确的前后衔接。与 M6-2 Chunk 拼接协作：先补全相邻块，再拼接去重叠。

