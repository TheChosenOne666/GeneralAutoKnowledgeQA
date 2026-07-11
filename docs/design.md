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
| 缓存 | Redis | 会话缓存 |
| **AI 服务** | **Python + FastAPI + LangChain** | RAG 检索、Agent 推理、文档处理 |
| 向量数据库 | Milvus | 文档向量存储与检索 |
| 文档解析 | PyMuPDF / python-docx / unstructured | |

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
  { key: 'search', label: '搜索', roles: ['member', 'tenant_admin'] },
  { key: 'ai-config', label: 'AI模型配置', roles: ['member', 'tenant_admin'] },
  { key: 'members', label: '成员管理', roles: ['tenant_admin'] },
  { key: 'audit', label: '审计日志', roles: ['tenant_admin', 'super_admin'] },
];
```

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
文本提取（Unstructured / PyMuPDF）
  │
  ▼
分块（Chunking）
  ├── 语义分块：按段落/标题
  ├── 固定长度：512 tokens，重叠 50
  └── 表格/代码特殊处理
  │
  ▼
向量化（Embedding API）
  │
  ▼
存储（向量数据库 + 元数据）
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

> 返回 `Flux<String>` SSE 流，Java 透传 Python AI 服务的流式响应。

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
├── docker-compose.yml              # PostgreSQL + Redis
└── docs/
    ├── requirements.md
    ├── design.md
    ├── ui-design-guide.md
    ├── task-breakdown.md
    └── ui-design/
```
