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
| **AI 服务** | **Python + FastAPI + LangChain** | RAG 检索、Agent 推理、文档处理 |
| 向量数据库 | Milvus | 文档向量存储与检索 |
| 文档解析 | PyMuPDF（PDF）/ python-docx（DOCX）/ MD·TXT 直读 + LangChain RecursiveCharacterTextSplitter 分块 | 未引入 unstructured |

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
- `POST /create`：创建租户，并把指定**已注册**邮箱用户设为该租户首个 `tenant_admin`（对齐 WeKnora EnsureOwner）；slug 全局唯一；拒绝把 `super_admin` 设为租户管理员。
- `POST /{id}/status`：`active` ↔ `suspended` 启用停用。
- `POST /{id}/quota`：设置成员数 / 文档数上限（`<=0` 视为不限）。

**配额执行（对齐 WeKnora）**：`DocumentServiceImpl.uploadDocument` 与 `UserServiceImpl` 邀请/注册入租户时，校验 `Tenant.maxDocuments` / `maxMembers`，达到上限即拒绝（`OPERATION_ERROR`）。

**平台默认 AI 配置**：`AiConfigController` 新增 `GET/POST /ai-config/platform-default`（仅超管），以 `tenant_id=0` 哨兵行作为全租户兜底；用户配置回退链：`用户级 → 租户级 → 平台级`。前端 `AIConfigPage` 在超管视角下提供「我的配置 / 平台默认配置」作用域切换。

**全局审计日志**：`AuditLogController.listLogs` 对 `super_admin` 走 `listAllLogs`（跨租户），对 `tenant_admin` 走 `listLogsByTenant`（本租户）。

**超管切换租户操作（方案A，对齐 WeKnora TenantSelector）**：平台超管除平台级页面（租户管理 / 全局审计 / 平台默认 AI 配置）外，还常以"某个租户管理员"身份进入其知识库、成员、对话、租户级 AI 配置。采用**租户上下文切换**而非新建跨租户列表页：

- **后端**：`UserServiceImpl.getLoginUser` 解析出登录用户后，若角色为 `super_admin` 且请求头携带 `X-Tenant-ID`（`CommonConstant.TENANT_HEADER`），则将其 `tenantId` 临时覆盖为该请求头值（仅内存对象，不写库）；普通用户忽略该头，防止越权切换租户。`AuthInterceptor` 对 `super_admin` 直接放行全部接口，故切进某租户后可访问其 `tenant_admin` 专属业务接口（如成员管理 `@AuthCheck(mustRole=tenant_admin)`）。
- **前端**：新增 `TenantContext`（`context/TenantContext.tsx`），超管登录后加载全部租户并默认选中 `localStorage` 中已选（否则第一个）；切换器写入 `localStorage` 的 `xiongda_current_tenant`。`api/client.ts` 请求拦截器携带 `X-Tenant-ID` 头。`AppLayout` 仅对超管渲染租户切换器，并将其有效角色映射为可见 `tenant_admin` 菜单（知识库 / 成员管理），同时主内容区以 `currentTenantId` 为 `key` 重挂，触发各业务页重新加载该租户数据；`ChatContext` 在 `currentTenantId` 变化时清空并刷新对话列表。
- **效果**：超管在侧边栏切到「租户A」后，知识库 / 成员管理 / 对话 / AI 模型配置均按租户A展示与操作，复用既有页面与接口，无需新建跨租户列表。

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
    status      VARCHAR(20) DEFAULT 'pending',
    -- pending → parsing → embedding → ready / failed
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
Rerank 精排（Rerank API，Top-N=5）
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

### 5.3 Agent 多步推理

复杂问题触发 ReAct Agent：

```
思考(Thought) → 行动(Action) → 观察(Observation) → 思考 → ... → 最终回答

示例：
Q: "我们公司的年假政策是什么？和病假有什么区别？"
Thought: 需要查找年假和病假政策
Action: search("年假政策")
Observation: 找到员工手册第3章...
Action: search("病假政策")
Observation: 找到员工手册第4章...
Thought: 对比两者差异
Final Answer: 年假...病假...区别...
```

---

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
│   │   │   ├── ChatController.java     # 会话 + SSE 透传
│   │   │   ├── KnowledgeBaseController.java
│   │   │   ├── AiConfigController.java
│   │   │   └── AuditLogController.java
│   │   ├── exception/
│   │   │   ├── BusinessException.java  # 自定义业务异常
│   │   │   ├── ThrowUtils.java         # throwIf() 工具
│   │   │   └── GlobalExceptionHandler.java
│   │   ├── mapper/                     # MyBatis-Plus BaseMapper (8个)
│   │   ├── model/
│   │   │   ├── entity/                 # @TableName 实体 (8个)
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
│   │   ├── chat.py                 # /ai/chat/stream SSE
│   │   └── document.py             # /ai/document/process
│   └── services/
│       ├── llm.py                  # LangChain LLM 流式
│       ├── embedding.py
│       ├── vector_store.py         # Milvus
│       ├── rag.py                  # 混合检索 + Rerank
│       └── document_processor.py
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
> - Python：新增 `core/redis_client.py`（Redis 异步客户端单例）、`routers/cache.py`（`POST /ai/cache/invalidate` 失效接口）、`services/model_config.py`（`ModelConfigError` 模型配置错误类型，M3-3 取消静默降级）。
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
   上传文档  POST /api/knowledge/document/upload（MultipartFile）
        → Document 状态机：pending → parsing → embedding → ready / failed（前端轮询列表状态）
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
   → DocumentServiceImpl：保存文件（绝对路径）+ 创建 Document(status=pending)
   │
   ▼  CompletableFuture.runAsync（异步，不阻塞上传响应）
Java DocumentServiceImpl.triggerDocumentProcessing
   → 更新 status=parsing
   → AiServiceClient.processDocument（HTTP POST → Python）
   │
   ▼
Python POST /ai/document/process → DocumentProcessor.process()
   1) extract_text：PDF(PyMuPDF) / DOCX(python-docx) / MD·TXT(直读)
   2) chunk_text：RecursiveCharacterTextSplitter(512 / 50)
   3) embed_chunks：Embedding（命中 L2 缓存跳过 API）→ 向量
   4) store_chunks：写入向量库（元数据 doc_id/kb_id/tenant_id/source/page）
   → 返回 chunk_count
   │
   ▼
Java 更新 status=ready + chunkCount
   → 调 AiServiceClient.invalidateCache(tenant) 清 L1 检索缓存（见 §9.5）
        │
   └─ 失败分支：更新 status=failed + errorMsg（default 异常兜底）
```

> 文档删除（POST /api/knowledge/document/delete，逻辑删除）同样触发 `invalidateCache`，保证下次提问回源重新检索。

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
   ├─ mode == "rag" → rag_service.retrieve(question, kb_ids, tenant_id)
   │     ├─【L1 检索缓存】命中 retrieval:{tenant}:{q_hash} → 直接返回，跳过向量/BM25/RRF/Rerank
   │     └─ 未命中：
   │           ├─【L2 嵌入缓存】embedding:{text}:{model} 命中 → 复用 query 向量，跳过 Embedding API
   │           ├─ 向量检索 Top-K=20 + BM25 关键词检索 Top-K=20
   │           ├─ RRF 融合去重（k=60）
   │           ├─ Rerank 精排 Top-N=5（仅当配置 rerank_api_key；否则取前 5）
   │           └─ 写 L1 缓存（TTL 3600s）
   │     检索失败 → 降级无上下文问答
   ├─ 有 sources → 发 event: sources（引用来源：文件名 / 页码 / 内容片段）
   ├─ LLM stream_generate（携带 history 多轮上下文）→ 逐 token 发 event: token
   └─ 发 event: done（conversation_id, sources）
```

> **助手回答持久化（已修复）**：Java 在透传 SSE 流时聚合 `token` 事件累积回答文本、捕获 `done` 事件的 `sources`，流结束后调用 `saveAssistantMessage`（先写 L3 缓存再落库），刷新后历史可完整回看。
> **SSE 封装（已修复）**：Java 不再裸透传 Python 的 `data:` 文本碎片，而是解析每帧后重新包装成带 `event:` 的标准 SSE 发给前端（保留事件类型），前端 `ChatPage.tsx` 按 `event` 名分发（token 追加 / sources 渲染卡片 / done 结束并刷新）。**落库动作从响应式 Netty 线程 offload 到 `Schedulers.boundedElastic()` 并加 try-catch，避免阻塞 I/O 线程且异常不被静默吞掉。**
> **前端停止生成（已修复）**：流式未正常结束（如 LLM 响应极慢、HMR 热更新保留状态）会让 `streaming` 卡在 `true`，导致输入框 `disabled` 无法输入。改用 `AbortController`：`streamChat` 透传 `signal`，生成中显示「停止」按钮可主动中断（`AbortError` 不报错、保留已生成内容）；切换会话时 `useEffect` 自动 `abort()` 旧流并重置 `streaming`，恢复输入态。
> **未配置模型常驻提示（M3-3 前端）**：进入问答页即调用 `GET /api/ai-config/`，按「provider 与 model 均非空」判定 LLM / Embedding 是否已配置（API Key 不在前端可见范围，故不纳入判定）；任一未配置则在消息区顶部渲染常驻琥珀色横幅并列出缺失项（仅 LLM / 仅 Embedding / 两者皆缺），附「去配置」跳转到 `/ai-config`。接口异常时静默忽略，不打扰对话。
> **模型配置正确性运行时检测（M3-3 已实现）**：上述常驻提示只覆盖"字段是否为空"。M3-3 额外覆盖**配置填了但填错**的情况——API Key 错误 / 模型名错误 / 提供商不匹配 / 向量维度不匹配，会导致上传文档向量化失败或对话 LLM 调用失败。落地：① Python 新增 `ModelConfigError` 并真正消费用户配置、**取消静默降级**（无 Key 直接抛错而非造假向量/假回答），调用失败 / 维度不匹配抛此错误；② Java `AiServiceClient.toAiConfigMap` 把配置以 `ai_config` 透传 Python，失败时解析 `error_type==MODEL_CONFIG_ERROR` 置 `document.modelConfigError`；③ 前端 `ChatPage` 解析 SSE `event: error` 的 `MODEL_CONFIG_ERROR` 渲染红色「模型配置不正确」横幅 + 跳转，`KnowledgeBasePage` 在文档 `modelConfigError` 时同样提示。与存在性常驻横幅构成"存在性+正确性"两层提示。

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

> 前端按 `event` 类型分发：`sources` → 渲染引用来源卡片；`token` → 追加到当前回答；`done` → 结束流式并刷新会话列表。

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
| Java | Python | `/ai/chat/stream` | HTTP SSE 透传（WebClient） |
| Java | Python | `/ai/document/process` | HTTP（异步触发） |
| Java | Python | `/ai/cache/invalidate` | HTTP（文档变更清 L1） |
| Python | 外部 AI | `/embeddings`、`/chat/completions`、`/rerank` | OpenAI 兼容（可选，未配则降级） |

