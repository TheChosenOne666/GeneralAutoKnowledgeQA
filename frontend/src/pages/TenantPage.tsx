/** 租户管理页 — 平台超管专用，对接真实租户管理 API（列表 / 创建 / 启用停用 / 配额）。*/

import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '@/hooks/useAuth'
import { tenantApi } from '@/api/tenant'
import type { Tenant, TenantCreateRequest, TenantQuotaRequest } from '@/types'

const PAGE_SIZE = 10

function formatDate(value?: unknown): string {
  if (value == null) return '—'
  const d = new Date(value as string | number)
  if (isNaN(d.getTime())) return '—'
  return d.toISOString().slice(0, 19).replace('T', ' ')
}

function quotaText(n: number | null): string {
  if (n == null || n <= 0) return '不限'
  return String(n)
}

export default function TenantPage() {
  const { user } = useAuth()
  const isSuperAdmin = user?.role === 'super_admin'

  const [tenants, setTenants] = useState<Tenant[]>([])
  const [total, setTotal] = useState(0)
  const [current, setCurrent] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState<string | number | null>(null)

  // 创建租户弹窗
  const [showCreate, setShowCreate] = useState(false)

  // 配额编辑弹窗
  const [quotaTarget, setQuotaTarget] = useState<Tenant | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const page = await tenantApi.listTenants(current, PAGE_SIZE)
      setTenants(page.records)
      setTotal(page.total)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '加载租户列表失败')
    } finally {
      setLoading(false)
    }
  }, [current])

  useEffect(() => {
    refresh()
  }, [refresh])

  const handleToggleStatus = async (t: Tenant) => {
    setBusyId(t.id)
    setError('')
    try {
      const next = t.status === 'active' ? 'suspended' : 'active'
      await tenantApi.setStatus(t.id, next)
      await refresh()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '操作失败')
    } finally {
      setBusyId(null)
    }
  }

  const handleSaveQuota = async (body: TenantQuotaRequest) => {
    if (!quotaTarget) return
    setBusyId(quotaTarget.id)
    setError('')
    try {
      await tenantApi.setQuota(quotaTarget.id, body)
      setQuotaTarget(null)
      await refresh()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '保存配额失败')
    } finally {
      setBusyId(null)
    }
  }

  const handleCreate = async (body: TenantCreateRequest) => {
    setBusyId('__create__')
    setError('')
    try {
      await tenantApi.createTenant(body)
      setShowCreate(false)
      setCurrent(1)
      await refresh()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '创建租户失败')
    } finally {
      setBusyId(null)
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">租户管理</h3>
        {isSuperAdmin && (
          <button
            onClick={() => setShowCreate(true)}
            className="px-4 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg flex items-center gap-2 shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
            </svg>
            创建租户
          </button>
        )}
      </div>

      <div className="flex-1 overflow-auto p-6">
        {error && <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>}

        <div className="bg-white rounded-xl border border-emerald-100 overflow-hidden">
          <table className="w-full">
            <thead className="bg-emerald-50/50 border-b border-emerald-100">
              <tr>
                {['租户名称', '标识', '状态', '成员数', '文档数', '配额（成员/文档）', '创建时间', '操作'].map((h) => (
                  <th
                    key={h}
                    className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-50">
              {loading ? (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-slate-400">
                    加载中…
                  </td>
                </tr>
              ) : tenants.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-sm text-slate-400">
                    暂无租户
                  </td>
                </tr>
              ) : (
                tenants.map((t) => {
                  const busy = busyId === t.id
                  const active = t.status === 'active'
                  return (
                    <tr key={t.id} className="hover:bg-emerald-50/40 transition">
                      <td className="px-4 py-3.5 text-sm text-slate-800 font-medium">{t.name}</td>
                      <td className="px-4 py-3.5 text-sm text-slate-500">{t.slug}</td>
                      <td className="px-4 py-3.5">
                        {active ? (
                          <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700">
                            启用
                          </span>
                        ) : (
                          <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-100 text-slate-500">
                            停用
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3.5 text-sm text-slate-600">{t.memberCount}</td>
                      <td className="px-4 py-3.5 text-sm text-slate-600">{t.docCount}</td>
                      <td className="px-4 py-3.5 text-sm text-slate-500">
                        {quotaText(t.maxMembers)} / {quotaText(t.maxDocuments)}
                      </td>
                      <td className="px-4 py-3.5 text-sm text-slate-500">{formatDate(t.createTime)}</td>
                      <td className="px-4 py-3.5">
                        <div className="flex items-center gap-1 flex-wrap">
                          <button
                            disabled={busy}
                            onClick={() => handleToggleStatus(t)}
                            className="px-2.5 py-1 rounded-md bg-amber-50 text-amber-700 text-xs font-semibold hover:bg-amber-100 transition disabled:opacity-50"
                          >
                            {active ? '停用' : '启用'}
                          </button>
                          <button
                            disabled={busy}
                            onClick={() => setQuotaTarget(t)}
                            className="px-2.5 py-1 rounded-md bg-emerald-50 text-brand-700 text-xs font-semibold hover:bg-emerald-100 transition disabled:opacity-50"
                          >
                            配额
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>

        {/* 分页 */}
        {total > 0 && (
          <div className="flex items-center justify-between mt-4 text-sm text-slate-500">
            <span>
              共 {total} 个租户，第 {current} / {totalPages} 页
            </span>
            <div className="flex items-center gap-2">
              <button
                disabled={current <= 1}
                onClick={() => setCurrent((c) => Math.max(1, c - 1))}
                className="px-3 py-1.5 rounded-lg border border-emerald-100 text-slate-600 hover:bg-emerald-50 disabled:opacity-40 disabled:cursor-not-allowed transition"
              >
                上一页
              </button>
              <button
                disabled={current >= totalPages}
                onClick={() => setCurrent((c) => Math.min(totalPages, c + 1))}
                className="px-3 py-1.5 rounded-lg border border-emerald-100 text-slate-600 hover:bg-emerald-50 disabled:opacity-40 disabled:cursor-not-allowed transition"
              >
                下一页
              </button>
            </div>
          </div>
        )}
      </div>

      {showCreate && (
        <CreateTenantModal
          saving={busyId === '__create__'}
          onClose={() => setShowCreate(false)}
          onSubmit={handleCreate}
        />
      )}

      {quotaTarget && (
        <QuotaModal
          tenant={quotaTarget}
          saving={busyId === quotaTarget.id}
          onClose={() => setQuotaTarget(null)}
          onSubmit={handleSaveQuota}
        />
      )}
    </div>
  )
}

/** 创建租户弹窗。*/
function CreateTenantModal({
  saving,
  onClose,
  onSubmit,
}: {
  saving: boolean
  onClose: () => void
  onSubmit: (body: TenantCreateRequest) => void
}) {
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [maxMembers, setMaxMembers] = useState('')
  const [maxDocuments, setMaxDocuments] = useState('')
  const [formError, setFormError] = useState('')

  const submit = () => {
    setFormError('')
    if (!name.trim()) return setFormError('请输入租户名称')
    if (!slug.trim()) return setFormError('请输入租户标识')
    if (!/^[a-z0-9-]+$/.test(slug.trim()))
      return setFormError('标识只能包含小写字母、数字和连字符')
    if (!adminEmail.trim()) return setFormError('请输入管理员邮箱')
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(adminEmail.trim()))
      return setFormError('管理员邮箱格式不正确')
    const req: TenantCreateRequest = {
      name: name.trim(),
      slug: slug.trim(),
      adminEmail: adminEmail.trim(),
      maxMembers: maxMembers ? Number(maxMembers) : null,
      maxDocuments: maxDocuments ? Number(maxDocuments) : null,
    }
    onSubmit(req)
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-2xl w-full max-w-md p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h4 className="text-lg font-bold text-slate-800">创建租户</h4>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div className="space-y-4">
          {formError && <div className="px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{formError}</div>}
          <ModalField label="租户名称" value={name} onChange={setName} placeholder="如 研发部" />
          <ModalField label="租户标识" value={slug} onChange={setSlug} placeholder="如 dev-team（小写字母/数字/连字符）" />
          <ModalField label="管理员邮箱" value={adminEmail} onChange={setAdminEmail} placeholder="该邮箱需已注册" type="email" />
          <ModalField label="成员上限（留空=默认 50）" value={maxMembers} onChange={setMaxMembers} placeholder="50" type="number" />
          <ModalField label="文档上限（留空=默认 1000）" value={maxDocuments} onChange={setMaxDocuments} placeholder="1000" type="number" />
          <p className="text-xs text-slate-400">
            创建后，该邮箱对应的已注册用户将自动成为本租户的首个管理员。
          </p>
          <button
            onClick={submit}
            disabled={saving}
            className="w-full py-3 rounded-xl bg-gradient-to-r from-brand-600 to-brand-500 text-white font-semibold text-sm hover:opacity-90 transition disabled:opacity-50"
          >
            {saving ? '创建中…' : '创建租户'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** 配额编辑弹窗。*/
function QuotaModal({
  tenant,
  saving,
  onClose,
  onSubmit,
}: {
  tenant: Tenant
  saving: boolean
  onClose: () => void
  onSubmit: (body: TenantQuotaRequest) => void
}) {
  const [maxMembers, setMaxMembers] = useState(tenant.maxMembers ? String(tenant.maxMembers) : '')
  const [maxDocuments, setMaxDocuments] = useState(tenant.maxDocuments ? String(tenant.maxDocuments) : '')

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-2xl w-full max-w-md p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h4 className="text-lg font-bold text-slate-800">编辑配额 · {tenant.name}</h4>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div className="space-y-4">
          <ModalField
            label="成员上限（0 或不填=不限）"
            value={maxMembers}
            onChange={setMaxMembers}
            placeholder="50"
            type="number"
          />
          <ModalField
            label="文档上限（0 或不填=不限）"
            value={maxDocuments}
            onChange={setMaxDocuments}
            placeholder="1000"
            type="number"
          />
          <button
            onClick={() =>
              onSubmit({
                maxMembers: maxMembers ? Number(maxMembers) : null,
                maxDocuments: maxDocuments ? Number(maxDocuments) : null,
              })
            }
            disabled={saving}
            className="w-full py-3 rounded-xl bg-gradient-to-r from-brand-600 to-brand-500 text-white font-semibold text-sm hover:opacity-90 transition disabled:opacity-50"
          >
            {saving ? '保存中…' : '保存配额'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** 弹窗文本输入项。*/
function ModalField({
  label,
  value,
  onChange,
  placeholder,
  type = 'text',
}: {
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: string
}) {
  return (
    <div>
      <label className="block text-sm font-medium text-slate-600 mb-2">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full px-4 py-3 rounded-xl border border-emerald-100 outline-none text-sm focus:border-brand-400 transition"
      />
    </div>
  )
}
