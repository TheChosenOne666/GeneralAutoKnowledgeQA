/** 租户管理接口 — 仅平台超管可访问，对齐后端 TenantController。*/

import { api } from './client'
import type { BaseResponse, Page, Tenant, TenantCreateRequest, TenantQuotaRequest } from '@/types'

export const tenantApi = {
  /** 分页列出所有租户（含实时成员数 / 文档数）。*/
  listTenants: async (current = 1, size = 10): Promise<Page<Tenant>> => {
    const res = await api.get<BaseResponse<Page<Tenant>>>('/tenant/list', {
      params: { current, pageSize: size },
    })
    return res.data.data
  },

  /** 创建租户并将指定邮箱用户设为首个租户管理员。*/
  createTenant: async (body: TenantCreateRequest): Promise<Tenant> => {
    const res = await api.post<BaseResponse<Tenant>>('/tenant/create', body)
    return res.data.data
  },

  /** 启用 / 停用租户（status = active | suspended）。*/
  setStatus: async (id: string | number, status: string): Promise<Tenant> => {
    const res = await api.post<BaseResponse<Tenant>>(`/tenant/${id}/status`, null, {
      params: { status },
    })
    return res.data.data
  },

  /** 设置租户配额（成员数 / 文档数上限）。*/
  setQuota: async (id: string | number, body: TenantQuotaRequest): Promise<Tenant> => {
    const res = await api.post<BaseResponse<Tenant>>(`/tenant/${id}/quota`, body)
    return res.data.data
  },
}
