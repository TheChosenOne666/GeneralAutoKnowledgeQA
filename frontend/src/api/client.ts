/** Axios HTTP 客户端 — 自动携带 JWT。*/

import axios from 'axios'

const TOKEN_KEY = 'xiongda_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export const api = axios.create({
  baseURL: '/api',  // 通过 Vite 代理转发到 Java 后端 (localhost:8080)
  timeout: 30000,
})

// 请求拦截器：注入 Authorization
api.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // 平台超管切换租户时携带目标租户ID（仅超管在 TenantContext 中写入该 key，普通用户不写，故不会带）
  const tenantId = window.localStorage.getItem('xiongda_current_tenant')
  if (tenantId) {
    config.headers['X-Tenant-ID'] = tenantId
  }
  return config
})

// 响应拦截器：统一处理 BaseResponse 错误码
api.interceptors.response.use(
  (response) => {
    const data = response.data
    // 后端统一响应 BaseResponse {code, data, message}
    if (data && typeof data.code === 'number') {
      if (data.code === 0) {
        // 成功：直接返回 data
        return response
      }
      // 未登录
      if (data.code === 40100) {
        clearToken()
        window.location.href = '/login'
      }
      // 业务错误：抛出消息
      return Promise.reject(new Error(data.message || '操作失败'))
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      clearToken()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)
