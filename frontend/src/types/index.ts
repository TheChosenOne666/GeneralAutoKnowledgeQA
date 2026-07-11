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
  token_type: string
  user: User
}

export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  scope: 'shared' | 'personal'
  owner_id: string
  document_count: number
  created_at: string
}

export interface Document {
  id: string
  kb_id: string
  filename: string
  file_type: string
  file_size: number
  status: 'pending' | 'parsing' | 'embedding' | 'ready' | 'failed'
  chunk_count: number
  error_msg: string | null
  created_at: string
}

export interface Conversation {
  id: string
  title: string
  message_count: number
  created_at: string
  updated_at: string
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources: { filename: string; page: number }[] | null
  model: string | null
  created_at: string
}

export interface AIConfig {
  llm_provider: string
  llm_model: string
  llm_base_url: string
  llm_temperature: number
  llm_max_tokens: number
  embedding_provider: string
  embedding_model: string
  embedding_base_url: string
  embedding_dimension: number
  rerank_provider: string | null
  rerank_model: string | null
  has_rerank: boolean
}

export interface Member {
  id: string
  name: string
  email: string
  role: Role
  is_active: boolean
  avatar_url: string | null
  last_active_at: string | null
  created_at: string
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
