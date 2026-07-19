/** 全局类型定义。*/

/** 后端统一响应封装。*/
export interface BaseResponse<T> {
  code: number
  data: T
  message: string
}

export type Role = 'super_admin' | 'tenant_admin' | 'member'

export interface User {
  id: string
  name: string
  email: string
  role: Role
  tenantId: string | null
  avatarUrl: string | null
}

/** 登录返回 VO（含 token）。*/
export interface LoginUserVO {
  id: string
  name: string
  email: string
  role: Role
  tenantId: string | null
  avatarUrl: string | null
  token: string
}

/** 兼容旧引用。*/
export type TokenResponse = LoginUserVO

export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  scope: 'shared' | 'personal'
  ownerId: string
  documentCount: number
  createTime: string
}

export interface Document {
  id: string
  kbId: string
  filename: string
  fileType: string
  fileSize: number
  status: 'processing' | 'parsing' | 'retrieving' | 'optimizing' | 'ready' | 'failed' | 'cancelled'
  chunkCount: number
  errorMsg: string | null
  /** 是否因 AI 模型配置错误导致处理失败（用于前端提示重配）。*/
  modelConfigError: boolean | null
  /** 是否因模型额度不足 / 被限流（HTTP 429 / 5xx 过载 / 余额耗尽）导致失败（区别于配置错误，提示重试 / 检查额度）。*/
  quotaError: boolean | null
  /**
   * 文档处理阶段时间线（M5-4）：JSON 数组，记录 解析/分块/向量化/入库/增强 各阶段的状态、
   * 起止时间、耗时与指标，供细粒度展示进度与失败定位。后端以 JSON 字符串返回，前端按需解析。
   */
  processStages?: ProcessStage[] | string | null
  createTime: string
}

/** 文档处理阶段（M5-4 阶段化 span 时间线追踪）。*/
export interface ProcessStage {
  stage: string
  status: 'active' | 'done' | 'failed'
  startedAt?: number
  endedAt?: number
  elapsedMs?: number
  error?: string | null
  metrics?: Record<string, unknown> | null
}

export interface Conversation {
  id: string
  title: string
  messageCount: number
  createTime: string
  updateTime: string
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources: string | null
  model: string | null
  /** 后端返回数字时间戳（JSON 序列化 Date 所得）或字符串，前端统一兼容。*/
  createTime: string | number
}

/** 引用来源（RAG 检索片段，后端以 JSON 字符串存于 Message.sources）。*/
export interface SourceItem {
  content: string
  source: string
  page: number
  score: number
  doc_id: string
  kb_id: string
  chunk_index: number
}

/** 多轮对话历史项（不含当前问题）。*/
export interface ChatHistoryItem {
  role: 'user' | 'assistant'
  content: string
}

/** 单条 Agent 推理步骤（M4-1 智能推理模式）。*/
export interface AgentStep {
  step: number
  type: 'thought' | 'action' | 'observation'
  content: string
  tool?: string
  input?: string
  success?: boolean
}

/** 问答页单条消息（含流式中间态），跨路由切换保留于 ChatContext 内存缓存。*/
export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  sources?: SourceItem[]
  /** M4-1：智能推理（Agent）模式的推理步骤树。*/
  agentSteps?: AgentStep[]
  /** 该消息产生时使用的问答模式。*/
  mode?: 'rag' | 'web' | 'agent'
  /** 消息时间（YYYY-MM-DD HH:mm），用于展示在用户消息下方。*/
  time?: string
}

/** AI 模型配置（对齐后端 AiConfigVO，字段为 camelCase；不含 API Key 明文）。*/
export interface AIConfig {
  llmProvider: string | null
  llmModel: string | null
  llmModels: string[]
  llmBaseUrl: string | null
  llmTemperature: number | null
  llmMaxTokens: number | null
  embeddingProvider: string | null
  embeddingModel: string | null
  embeddingBaseUrl: string | null
  embeddingDimension: number | null
  rerankProvider: string | null
  rerankModel: string | null
  hasRerank: boolean
}

/** AI 模型配置更新请求（对齐后端 AiConfigUpdateRequest；API Key 为可选明文）。*/
export interface AIConfigUpdateRequest {
  llmProvider?: string | null
  llmModel?: string | null
  llmModels?: string[] | null
  llmApiKey?: string | null
  llmBaseUrl?: string | null
  llmTemperature?: number | null
  llmMaxTokens?: number | null
  embeddingProvider?: string | null
  embeddingModel?: string | null
  embeddingApiKey?: string | null
  embeddingBaseUrl?: string | null
  embeddingDimension?: number | null
  rerankProvider?: string | null
  rerankModel?: string | null
  rerankApiKey?: string | null
}

export interface Member {
  id: string
  name: string
  email: string
  role: Role
  tenantId: string | null
  avatarUrl: string | null
  isActive: number
  lastActiveAt: string | null
  createTime: string
}

/** 生成邀请链接结果。*/
export interface InviteResultVO {
  token: string
  inviteUrl: string
  role: Role
  expiresAt: string
}

/** 邀请链接详情（注册页展示）。*/
export interface InviteInfoVO {
  inviterName: string
  tenantName: string
  role: Role
}

/** MyBatis-Plus 分页响应（对齐后端 Page<T>）。*/
export interface Page<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** 租户视图对象（对齐后端 TenantVO，含实时成员数 / 文档数）。*/
export interface Tenant {
  id: string | number
  name: string
  slug: string
  /** active / suspended */
  status: string
  maxMembers: number | null
  maxDocuments: number | null
  memberCount: number
  docCount: number
  createTime: string
}

/** 创建租户请求（平台超管）。*/
export interface TenantCreateRequest {
  name: string
  slug: string
  maxMembers?: number | null
  maxDocuments?: number | null
  adminEmail: string
}

/** 租户配额设置请求。*/
export interface TenantQuotaRequest {
  maxMembers?: number | null
  maxDocuments?: number | null
}

/** 审计日志（对齐后端 AuditLogVO，camelCase；detail 为 JSON 字符串）。*/
export interface AuditLog {
  id: string
  userEmail: string
  action: string
  resourceType: string | null
  resourceId: string | null
  detail: string | null
  ipAddress: string | null
  createTime: string
}

/** M6-1：租户级检索配置（RRF 参数等）。*/
export interface RetrievalConfig {
  rrf_k: number
  rrf_vector_weight: number
  rrf_keyword_weight: number
  vector_min_relevance: number
  bm25_min_relevance: number
  rerank_min_relevance: number
  relative_ratio: number
  max_chunks_per_doc: number
}
