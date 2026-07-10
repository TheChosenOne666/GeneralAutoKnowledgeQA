# 熊答 — UI 设计文档

> UI 设计规范与页面清单

## 1. 品牌标识

### 1.1 品牌信息

| 项 | 值 |
|---|---|
| 网站名 | 熊答（BearAnswer） |
| Logo | 熊猫头像（黑白国宝 + 翡翠绿科技点缀，3D 渲染风格） |
| Logo 文件 | `docs/ui-design/logo/Adorable_giant_panda_head_masc_2026-07-10T17-53-27.png` |
| Slogan | 让企业知识真正流动起来 |

### 1.2 Logo 规格

| 使用场景 | 尺寸 | 圆角 |
|---|---|---|
| 侧边栏顶部 | 40×40px | rounded-lg |
| 登录页大屏 | 64×64px | rounded-2xl |
| 登录页小屏 | 48×48px | rounded-xl |
| 索引页 | 80×80px | rounded-2xl |

> Logo 图片为透明背景 PNG，仅显示熊猫本身，无白色方形边框。

---

## 2. 配色规范

### 2.1 主色调

浅绿主题（Emerald），清新自然的薄荷绿风格。

| 色阶 | Hex | 用途 |
|---|---|---|
| brand-50 | `#ECFDF5` | 背景底色、Tab 未选中 |
| brand-100 | `#D1FAE5` | 边框、滚动条 |
| brand-200 | `#A7F3D0` | 悬浮边框 |
| brand-300 | `#6EE7B7` | 次要图标 |
| brand-400 | `#34D399` | 渐变终点 |
| brand-500 | `#10B981` | **品牌主色** |
| brand-600 | `#059669` | 按钮主色、文字 hover |
| brand-700 | `#047857` | 深色文字 |

### 2.2 辅助色

| 色彩 | Hex | 用途 |
|---|---|---|
| Teal-400 | `#2DD4BF` | 渐变辅助色 |
| Slate-400 | `#94A3B8` | 次要文字 |
| Slate-500 | `#64748B` | 正文文字 |
| Slate-600 | `#475569` | 主要文字 |
| Slate-800 | `#1E293B` | 标题文字 |
| White | `#FFFFFF` | 卡片背景、侧边栏 |

### 2.3 渐变

```css
/* 按钮渐变 */
background: linear-gradient(to right, #059669, #10B981);

/* Logo 容器阴影 */
box-shadow: 0 8px 32px rgba(16, 185, 129, 0.35);

/* 输入框聚焦光晕 */
box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.12);
```

---

## 3. 布局规范

### 3.1 全局布局

```
┌────────┬─────────────────────────┐
│        │                         │
│ 侧边栏  │       主内容区           │
│ 240px  │                         │
│        │                         │
└────────┴─────────────────────────┘
```

- 侧边栏：固定 240px，白色背景 + 右侧 emerald 边框
- 主内容区：弹性宽度，浅绿背景 `bg-emerald-50/30`
- 最小宽度：1024px（桌面优先）

### 3.2 侧边栏结构

```
┌────────────────────┐
│  Logo + "熊答"      │  ← h-16, border-b
├────────────────────┤
│  📝 对话            │  ← 菜单项
│  📚 知识库          │
│  🔍 搜索            │
│  ⚙️ AI模型配置      │
│  👥 成员管理        │
│  📋 审计日志        │
├────────────────────┤
│  历史会话列表       │  ← 仅对话页显示
│  · 近7天            │
│  · 会话1            │
│  · 会话2            │
├────────────────────┤
│  👤 用户信息        │  ← 底部
└────────────────────┘
```

### 3.3 菜单项样式

| 状态 | 样式 |
|---|---|
| 默认 | `text-slate-500`，hover 背景 `#F0FDF4` |
| 选中 | `bg-emerald-50`，`text-emerald-600`，左侧 3px 绿色边框 |

---

## 4. 页面清单

### 4.1 设计稿文件

| 序号 | 文件 | 页面 | 说明 |
|---|---|---|---|
| — | `index.html` | 索引页 | 设计稿导航入口 |
| 1 | `01-auth.html` | 登录/注册 | 粒子动效 + 毛玻璃 + 密码强度 |
| 2 | `02-chat.html` | 问答页 | WeKnora 风格，大标题 + 推荐问题 + 底部输入框 |
| 3 | `03-knowledge-base.html` | 知识库管理 | 共享/个人 Tab + 上传 + 文档列表 |
| 4 | `04-ai-config.html` | AI 模型配置 | LLM/Embedding/Rerank 配置卡 |
| 5 | `05-members.html` | 成员管理 | 成员表 + 权限矩阵 |
| 6 | `06-audit-log.html` | 审计日志 | 筛选 + 日志表 + JSON 详情 |

### 4.2 页面详细说明

#### 页面 1：登录/注册（`01-auth.html`）

**布局**：左右分栏

- **左侧品牌展示区**（大屏显示）：
  - Logo + "熊答" 品牌名
  - 大标题："让企业知识真正流动起来"（shimmer 流光动画）
  - 副标题：基于 RAG + Agent 的多租户智能问答平台
  - 3个特性卖点（混合检索、多租户RBAC、ReAct Agent）

- **右侧认证卡片**（毛玻璃）：
  - Logo（小屏显示）
  - Tab 切换：登录 / 注册
  - 登录表单：邮箱 + 密码（可见切换）+ 社交登录（GitHub/Google）
  - 注册表单：姓名 + 邮箱 + 密码 + 确认密码 + 协议勾选
  - 密码强度检测：4格强度条（红/橙/黄/绿）

**动效**：
- Canvas 粒子系统（90个粒子 + 距离连线 + 鼠标交互吸引）
- 极光光晕（Emerald + Teal 渐变缓慢飘移）
- 卡片淡入动画

#### 页面 2：问答（`02-chat.html`）

**布局**：WeKnora 风格

- **左侧**：图标菜单 + 历史会话列表（近7天）
- **中间**：
  - 顶部大标题："基于知识库内容问答 – AI 问答"
  - 副标题："你可以这样问我"
  - 推荐问题胶囊（点击直接提问）
  - 底部悬浮输入框
  - 知识库选择标签
  - 工具栏：智能推理下拉、图片、附件、绿色+号、模型选择 `deepseek-v3.2`

#### 页面 3：知识库管理（`03-knowledge-base.html`）

- **Tab 切换**：共享知识库 / 我的知识库（带数量角标）
- **上传区**：拖拽上传，支持 PDF/Word/MD/TXT
- **文档列表**：表格（文件名、类型、大小、状态、上传时间、操作）
- **状态追踪**：pending → parsing → embedding → ready / failed
- **筛选**：按类型、状态

#### 页面 4：AI 模型配置（`04-ai-config.html`）

- **顶部状态栏**：LLM / Embedding / Rerank 当前配置状态
- **配置卡片**（2列网格）：
  - LLM：提供商、模型、API Key、温度、最大Token
  - Embedding：提供商、模型、API Key、向量维度
  - Rerank：提供商、模型、API Key
- 保存配置按钮

#### 页面 5：成员管理（`05-members.html`）

- **成员表格**：头像、姓名、邮箱、角色徽章、状态、加入时间
- **操作**：邀请、改角色、停用/启用、移除
- **权限矩阵**：可视化展示各角色权限

#### 页面 6：审计日志（`06-audit-log.html`）

- **筛选栏**：时间范围、操作类型、用户、关键词
- **日志表格**：时间、用户、操作类型徽章、详情、IP
- **展开详情**：JSON 格式完整操作数据

---

## 5. 组件规范

### 5.1 按钮

| 类型 | 样式 |
|---|---|
| 主按钮 | `bg-gradient-to-r from-brand-600 to-brand-500 text-white` + glow 阴影 |
| 次按钮 | `bg-white border border-emerald-200 text-slate-600` |
| 图标按钮 | `w-8 h-8 rounded-lg`，hover 背景变色 |
| 悬浮 | `hover:translateY(-2px)` + 阴影增强 |

### 5.2 输入框

- 背景：白色半透明
- 边框：`border-emerald-200`
- 聚焦：`box-shadow: 0 0 0 3px rgba(16,185,129,0.12)`
- 圆角：`rounded-xl`

### 5.3 卡片

- 背景：白色
- 边框：`border-emerald-100`
- 圆角：`rounded-2xl`
- 悬浮：`box-shadow: 0 12px 40px rgba(16,185,129,0.12)` + 边框变 `#A7F3D0`

### 5.4 徽章

| 类型 | 样式 |
|---|---|
| 管理员 | `bg-emerald-100 text-emerald-700` |
| 普通成员 | `bg-slate-100 text-slate-600` |
| 成功 | `bg-emerald-100 text-emerald-600` |
| 失败 | `bg-red-100 text-red-600` |
| 进行中 | `bg-amber-100 text-amber-600` |

### 5.5 滚动条

```css
::-webkit-scrollbar { width: 5px; }
::-webkit-scrollbar-thumb { background: #D1FAE5; border-radius: 3px; }
::-webkit-scrollbar-track { background: transparent; }
```

---

## 6. 字体

| 用途 | 字体 | 字重 |
|---|---|---|
| 全局 | Inter | 400 / 500 / 600 / 700 / 800 |
| 标题 | Inter ExtraBold | 800 |
| 正文 | Inter Regular | 400 |
| 代码 | monospace | 400 |

```html
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
```

---

## 7. 响应式

| 断点 | 行为 |
|---|---|
| ≥ 1024px（lg） | 完整布局，侧边栏 + 主区域 |
| 768px–1023px（md） | 侧边栏可折叠，主区域全宽 |
| < 768px（sm） | 登录页隐藏左侧品牌区，仅显示认证卡片 |

> 当前设计稿以桌面端为主，移动端适配在开发阶段处理。

---

## 8. 动效规范

| 元素 | 动效 |
|---|---|
| 卡片悬浮 | `translateY(-6px)` + 阴影增强，0.25s ease |
| 菜单 hover | 背景色渐变，0.2s ease |
| 按钮悬浮 | `translateY(-2px)` + 阴影增强 |
| 输入框聚焦 | 光晕渐现，0.2s |
| 登录页粒子 | Canvas 60fps，粒子漂浮 + 连线 + 鼠标交互 |
| 极光光晕 | CSS animation，缓慢飘移 |
| 流光标题 | shimmer 动画，文字渐变扫光 |
| 表单切换 | 淡入动画 fade-in |
