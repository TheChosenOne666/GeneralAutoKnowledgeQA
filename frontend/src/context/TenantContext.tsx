/** 租户上下文 — 供平台超管切换当前操作的租户（对齐 业界的 TenantSelector）。
 *
 * 普通租户用户不使用本上下文：其租户由 JWT 决定，前端不携带 X-Tenant-ID。
 * 仅平台超管在切换器中选择租户后，会把 tenantId 写入 localStorage，
 * client.ts 拦截器据此在请求头带上 X-Tenant-ID，后端 getLoginUser 仅对 super_admin 采纳该头。 */

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { useAuth } from '@/hooks/useAuth'
import { tenantApi } from '@/api/tenant'
import type { Tenant } from '@/types'

// 注意：该 key 与 client.ts 中的常量保持一致
export const CURRENT_TENANT_KEY = 'xiongda_current_tenant'
const CURRENT_TENANT_NAME_KEY = 'xiongda_current_tenant_name'

interface TenantContextValue {
  /** 超管当前选中的租户ID；非超管为 null（其租户由 JWT 决定） */
  currentTenantId: string | number | null
  currentTenantName: string
  tenants: Tenant[]
  setTenant: (tenant: Tenant) => void
  loading: boolean
}

const TenantContext = createContext<TenantContextValue | null>(null)

export function TenantProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const [tenants, setTenants] = useState<Tenant[]>([])
  const [currentTenantId, setCurrentTenantId] = useState<string | number | null>(null)
  const [currentTenantName, setCurrentTenantName] = useState('')
  const [loading, setLoading] = useState(false)

  const isSuperAdmin = user?.role === 'super_admin'

  // 平台超管登录后加载租户列表，并默认选中 localStorage 中已选（否则第一个）
  useEffect(() => {
    if (!isSuperAdmin) {
      setTenants([])
      setCurrentTenantId(null)
      setCurrentTenantName('')
      return
    }
    let cancelled = false
    setLoading(true)
    tenantApi
      .listTenants(1, 1000)
      .then((page) => {
        if (cancelled) return
        const list = page.records ?? []
        setTenants(list)
        if (list.length === 0) {
          setCurrentTenantId(null)
          setCurrentTenantName('')
          return
        }
        const saved = window.localStorage.getItem(CURRENT_TENANT_KEY)
        const target = (saved != null ? list.find((t) => String(t.id) === saved) : undefined) ?? list[0]
        setCurrentTenantId(target.id)
        setCurrentTenantName(target.name)
        window.localStorage.setItem(CURRENT_TENANT_KEY, String(target.id))
        window.localStorage.setItem(CURRENT_TENANT_NAME_KEY, target.name)
      })
      .catch(() => {
        // 加载失败静默，避免阻塞页面
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [isSuperAdmin])

  const setTenant = (tenant: Tenant) => {
    setCurrentTenantId(tenant.id)
    setCurrentTenantName(tenant.name)
    window.localStorage.setItem(CURRENT_TENANT_KEY, String(tenant.id))
    window.localStorage.setItem(CURRENT_TENANT_NAME_KEY, tenant.name)
  }

  return (
    <TenantContext.Provider value={{ currentTenantId, currentTenantName, tenants, setTenant, loading }}>
      {children}
    </TenantContext.Provider>
  )
}

export function useTenant() {
  const ctx = useContext(TenantContext)
  if (!ctx) throw new Error('useTenant 必须在 TenantProvider 内使用')
  return ctx
}
