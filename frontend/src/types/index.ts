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
  status: 'pending' | 'parsing' | 'embedding' | 'ready' | 'failed'
  chunkCount: number
  errorMsg: string | null
  /** 是否因 AI 模型配置错误导致处理失败（用于前端提示重配）。*/
  modelConfigError: boolean | null
  createTime: string
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
  createTime: string
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

export interface AuditLog {
  id: string
  user_email: string
  action: string
  resource_type: string | null
  resource_id: string | null
  detail: Record<string, unknown> | null
  ip_address: string | null
  created_at: string
}
