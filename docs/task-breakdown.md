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

### M1-2 认证服务 [后端/Java] P0 · 1d

- 登录接口 `/api/user/login`（邮箱+密码 → JWT）
- 注册接口 `/api/user/register`（自动创建租户 + tenant_admin）
- 获取当前用户 `/api/user/get/login`
- JWT 过期/无效处理（GlobalExceptionHandler 拦截）
- 密码 MD5 + 盐值加密
- **依赖**: M1-1 ✅
- **产出**: Postman 可调通注册/登录/get/login

> 注：接口路径按模板风格（/api/user/login 而非 /api/auth/login）

---

### M1-3 认证页面 [前端] P0 · 1d

- 登录/注册 Tab 切换
- 表单验证（邮箱格式、密码长度）
- 调用后端 API，存储 JWT 到 localStorage
- 登录成功跳转 /chat
- 401 自动跳转登录页
- **依赖**: M1-2
- **产出**: 浏览器可注册登录

---

### M1-4 知识库基础 CRUD [后端/Java] P0 · 1d

- 创建知识库 `/api/knowledge/bases`
- 知识库列表（按 scope 筛选）
- 文档上传（MultipartFile → 保存文件 → 创建 Document 记录）
- 文档列表查询
- 文档删除
- **依赖**: M1-2
- **产出**: 可上传文件并查看列表

---

### M1-5 AI 服务基础 [Python] P0 · 1d

- FastAPI 服务启动（port 8001）
- `/health` 健康检查
- 文档处理接口 `/ai/document/process`（提取文本 → 分块）
  - PDF: PyMuPDF
  - DOCX: python-docx
  - MD/TXT: 直接读取
  - RecursiveCharacterTextSplitter 分块
- **依赖**: 无（独立服务）
- **产出**: 可处理文档返回分块

---

### M1-6 Java → Python 文档处理联动 [后端/Java] P0 · 0.5d

- 上传文档后异步调用 `AiServiceClient.processDocument()`
- 更新 Document 状态（pending → parsing → embedding → ready/failed）
- 文档列表返回处理状态
- **依赖**: M1-4, M1-5
- **产出**: 上传后自动处理，状态实时更新

---

### M1-7 知识库页面 [前端] P0 · 1d

- 共享/个人知识库 Tab 切换
- 拖拽上传区
- 文档列表表格（文件名、类型、大小、状态、时间）
- 状态徽章实时显示
- 新建知识库弹窗
- **依赖**: M1-4
- **产出**: 浏览器可上传文档查看状态

---

### M1-8 基础问答（无 RAG） [全栈] P0 · 1.5d

- **Python**: LangChain 接入 LLM（火山方舟/OpenAI），流式生成
- **Python**: `/ai/chat/stream` SSE 接口（LLM 直接回答，无检索）
- **Java**: ChatController SSE 透传
- **前端**: 问答页面（输入框 + 流式渲染回答）
- **前端**: 会话列表 + 新建会话
- **依赖**: M1-2, M1-5
- **产出**: 能和 AI 对话，流式输出

---

### M1-9 侧边栏布局 [前端] P0 · 0.5d

- AppLayout 组件（侧边栏 + 主内容区）
- 动态菜单（按角色过滤）
- 用户信息展示 + 退出登录
- 路由守卫 AuthGuard
- **依赖**: M1-3
- **产出**: 登录后进入带侧边栏的布局

---

**M1 合计: ~8.5 天**

---

## M2 — RAG 核心检索

### M2-1 Embedding 服务 [Python] P0 · 1d

- 接入 LangChain OpenAIEmbeddings（兼容火山方舟）
- 文本向量化 `embed_text()`
- 批量向量化 `embed_batch()`
- 配置 API Key / Base URL
- **依赖**: M1-5
- **产出**: 可调用 Embedding API

---

### M2-2 Milvus 向量存储 [Python] P0 · 1.5d

- Docker 启动 Milvus
- LangChain Milvus VectorStore 集成
- 存储文档分块向量（含元数据：doc_id, kb_id, tenant_id, page）
- 向量检索 `similarity_search_by_vector()`
- 按 tenant_id + kb_id 过滤
- 删除文档向量 `delete_by_doc()`
- **依赖**: M2-1
- **产出**: 文档可入库检索

---

### M2-3 文档处理全链路 [Python] P0 · 1d

- 完善 `document_processor.process()`：
  1. 提取文本
  2. 分块
  3. 向量化（Embedding）
  4. 存储（Milvus）
- 返回分块数量
- 错误处理 + 状态回写
- **依赖**: M2-1, M2-2
- **产出**: 上传文档自动向量化入库

---

### M2-4 RAG 检索服务 [Python] P0 · 1.5d

- `rag_service.retrieve()` 完整流程：
  1. Query 向量化
  2. 向量检索 Top-K=20
  3. BM25 关键词检索 Top-K=20（PostgreSQL 全文检索）
  4. 合并去重
  5. Rerank 精排 Top-N=5（可选，未配置则跳过）
- 返回 RetrievalResult（content, source, page, score）
- **依赖**: M2-2
- **产出**: 输入问题返回相关文档片段

---

### M2-5 RAG 流式问答 [全栈] P0 · 1d

- **Python**: `/ai/chat/stream` 接入 RAG
  - 检索 → 构建 Prompt（系统提示 + 检索上下文 + 问题）→ LLM 流式生成
  - SSE 推送 sources 事件（引用来源）
- **Java**: 透传 sources 事件
- **前端**: 渲染引用来源卡片（文件名 + 页码 + 查看原文）
- **前端**: Markdown 渲染 AI 回答（代码高亮）
- **依赖**: M2-4, M1-8
- **产出**: 问答有引用来源，回答基于知识库

---

### M2-6 会话管理完善 [全栈] P1 · 1d

- **Java**: 会话列表（按时间分组）、重命名、删除
- **前端**: 左侧历史会话列表
- **前端**: 点击会话加载历史消息
- **前端**: 多轮对话上下文（发送历史消息到后端）
- **依赖**: M1-8
- **产出**: 完整的多轮对话体验

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

---

### M3-3 AI 模型配置 [全栈] P1 · 1d

- **Java**: AI 配置读写（租户默认 + 用户级覆盖）
- **Python**: AI 服务从 Java 获取配置（LLM/Embedding/Rerank 参数）
- **前端**: AI 配置页面
  - LLM / Embedding / Rerank 配置卡片
  - 提供商下拉、模型输入、API Key、温度、Token 限制
  - 保存配置
  - 顶部状态栏（已配置/未配置）
- **依赖**: M1-2
- **产出**: 可在前端配置 AI 模型

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

### M4-6 问答页 UI 精细化 [前端] P2 · 1d

- 推荐问题胶囊
- 知识库选择标签
- 工具栏（智能推理下拉、模型选择、附件按钮）
- 输入框自动增高
- 对话气泡（用户右对齐 / AI 左对齐卡片）
- **依赖**: M2-6
- **产出**: 问答页与 UI 设计稿一致

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
M1-1 数据库
  └─→ M1-2 认证服务 ──→ M1-3 认证页面 ──→ M1-9 侧边栏布局
        │                    │
        ├─→ M1-4 知识库CRUD ──→ M1-7 知识库页面
        │      │
        │      └─→ M1-6 Java→Python联动
        │
        └─→ M1-8 基础问答 ──→ M2-5 RAG问答 ──→ M2-6 会话管理
               ↑                                    │
               │                                    └─→ M4-6 问答UI
M1-5 AI服务基础 ─┘
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
