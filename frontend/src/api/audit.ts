/** 审计日志 API — 分页查询 / 登出审计。*/

import { api } from './client'
import type { AuditLog, BaseResponse } from '@/types'

/** MyBatis-Plus Page<AuditLogVO> 序列化出的分页结果。*/
export interface AuditLogPage {
  records: AuditLog[]
  total: number
  size: number
  current: number
  pages: number
}

export interface AuditLogQuery {
  action?: string
  userEmail?: string
  startTime?: string
  endTime?: string
  current?: number
  pageSize?: number
}

export const auditApi = {
  /** 分页查询审计日志（租户管理员查本租户，平台超管查全局）。*/
  list: (params: AuditLogQuery) =>
    api.get<BaseResponse<AuditLogPage>>('/audit/list', { params }).then((r) => r.data.data),

  /** 登出（仅记录审计，JWT 无状态无需服务端清理）。*/
  logout: () => api.post<BaseResponse<boolean>>('/user/logout').then((r) => r.data.data),
}
