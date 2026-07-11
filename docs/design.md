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
| **后端** | **Java + Spring Boot 3** | 业务逻辑、认证、CRUD |
| 后端 ORM | Spring Data JPA (Hibernate) | |
| 后端安全 | Spring Security + JWT | RBAC 角色鉴权 |
| 后端 HTTP 客户端 | WebFlux (WebClient) | 调用 Python AI 服务 + SSE 透传 |
| 业务数据库 | PostgreSQL | 多租户行级隔离 |
| 缓存 | Redis | 会话缓存 |
| **AI 服务** | **Python + FastAPI + LangChain** | RAG 检索、Agent 推理、文档处理 |
| 向量数据库 | Milvus | 文档向量存储与检索 |
| 文档解析 | PyMuPDF / python-docx / unstructured | |
| 认证 | JWT + Spring Security | 无状态鉴权 |

---

## 2. 多租户设计

### 2.1 隔离策略

采用 **共享数据库 + 行级隔离**：

```sql
-- 每张业务表都带 tenant_id
CREATE TABLE knowledge_bases (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    name        VARCHAR(200),
    scope       VARCHAR(20) NOT NULL DEFAULT 'shared',  -- shared / personal
    owner_id    UUID,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    ...
);
```

### 2.2 租户上下文

```python
# 每次请求注入 tenant_id
async def get_tenant_id(request: Request) -> UUID:
    token = request.headers["Authorization"]
    payload = jwt_decode(token)
    return payload["tenant_id"]

# 查询自动过滤
@router.get("/knowledge-bases")
async def list_kb(tenant_id: UUID = Depends(get_tenant_id)):
    return await kb_service.list_by_tenant(tenant_id)
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

```python
class Role(str, Enum):
    SUPER_ADMIN = "super_admin"
    TENANT_ADMIN = "tenant_admin"
    MEMBER = "member"

# 装饰器鉴权
@require_role(Role.TENANT_ADMIN)
async def manage_members(...):
    ...

# 数据级权限：共享知识库
# member → 只读
# tenant_admin → 读写
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
CREATE TABLE knowledge_bases (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    name        VARCHAR(200) NOT NULL,
    scope       VARCHAR(20) NOT NULL DEFAULT 'shared',
    -- shared: 租户管理员维护，全员可问答
    -- personal: 个人维护，仅自己可问答
    owner_id    UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id       UUID NOT NULL REFERENCES knowledge_bases(id),
    tenant_id   UUID NOT NULL,
    filename    VARCHAR(500) NOT NULL,
    file_type   VARCHAR(20),     -- pdf / docx / md / txt
    file_size   BIGINT,
    status      VARCHAR(20) DEFAULT 'pending',
    -- pending → parsing → embedding → ready / failed
    chunk_count INTEGER DEFAULT 0,
    error_msg   TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
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
POST /api/chat/messages
Authorization: Bearer <token>
Content-Type: application/json

{
  "conversation_id": "uuid",
  "content": "公司年假政策是什么？",
  "kb_ids": ["uuid1", "uuid2"],
  "model": "deepseek-v3.2",
  "mode": "rag"  // rag / search
}
```

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

event: token
data: {"content": "员工手册"}

event: done
data: {"conversation_id": "uuid", "message_id": "uuid"}
```

---

## 7. 数据模型总览

### 7.1 核心表

| 表名 | 说明 |
|---|---|
| `tenants` | 租户表 |
| `users` | 用户表（含 role 字段） |
| `knowledge_bases` | 知识库表（含 scope 字段） |
| `documents` | 文档表 |
| `document_chunks` | 文档分块表（关联向量库） |
| `conversations` | 会话表 |
| `messages` | 消息表 |
| `ai_configs` | AI 模型配置表 |
| `audit_logs` | 审计日志表 |

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
├── backend/                        # Java Spring Boot 后端
│   ├── pom.xml
│   ├── src/main/java/com/xiongda/
│   │   ├── XiongdaApplication.java
│   │   ├── config/                 # CorsConfig, SecurityConfig
│   │   ├── controller/             # Auth, Chat, Knowledge, Member, AiConfig, Audit
│   │   ├── service/                # AuthService
│   │   ├── entity/                 # JPA 实体 (7 张表)
│   │   ├── repository/             # Spring Data JPA Repository
│   │   ├── dto/                    # 请求/响应 DTO
│   │   ├── security/               # JWT 工具、过滤器、上下文
│   │   └── client/                 # AiServiceClient (HTTP 调用 Python)
│   └── src/main/resources/
│       └── application.yml
├── ai-service/                     # Python AI 服务 (FastAPI + LangChain)
│   ├── main.py                     # 入口
│   ├── requirements.txt
│   ├── core/config.py              # 配置
│   ├── routers/
│   │   ├── chat.py                 # SSE 流式问答
│   │   └── document.py             # 文档处理
│   └── services/
│       ├── llm.py                  # LangChain LLM 流式生成
│       ├── embedding.py            # 文本向量化
│       ├── vector_store.py         # Milvus 向量检索
│       ├── rag.py                  # 混合检索 + Rerank
│       └── document_processor.py   # 文档解析 + 分块
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── hooks/
│   │   ├── api/
│   │   └── App.tsx
│   ├── package.json
│   └── tailwind.config.js
└── docs/
    ├── requirements.md             # 需求文档
    ├── design.md                   # 方案设计
    ├── ui-design-guide.md          # UI设计规范
    └── ui-design/                  # UI设计稿
```
