/** 成员管理页 — 对接真实成员管理 API（列表 / 改角色 / 停用启用 / 移除 / 邀请链接）。*/

import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '@/hooks/useAuth'
import { userApi } from '@/api/user'
import type { InviteResultVO, Member, Role } from '@/types'

const ROLE_LABELS: Record<Role, string> = {
  super_admin: '平台超管',
  tenant_admin: '租户管理员',
  member: '普通成员',
}

const ROLE_BADGE: Record<Role, string> = {
  super_admin: 'bg-purple-50 text-purple-700',
  tenant_admin: 'bg-emerald-50 text-emerald-700',
  member: 'bg-blue-50 text-blue-600',
}

type InviteRole = Exclude<Role, 'super_admin'>

function formatDate(value?: unknown): string {
  if (value == null) return '—'
  let d: Date
  if (Array.isArray(value)) {
    const n = value.map(Number)
    d = new Date(n[0] || NaN, (n[1] || 1) - 1, n[2] || 1)
  } else {
    d = new Date(value as string | number)
  }
  if (isNaN(d.getTime())) return '—'
  return d.toISOString().slice(0, 10)
}

export default function MembersPage() {
  const { user } = useAuth()
  const [members, setMembers] = useState<Member[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)

  // 邀请弹窗状态
  const [showInvite, setShowInvite] = useState(false)
  const [inviteName, setInviteName] = useState('')
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<InviteRole>('member')
  const [inviteLoading, setInviteLoading] = useState(false)
  const [inviteError, setInviteError] = useState('')
  const [inviteResult, setInviteResult] = useState<InviteResultVO | null>(null)
  const [copied, setCopied] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const list = await userApi.listMembers()
      setMembers(list)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '加载成员列表失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const isSelf = (m: Member) => String(m.id) === String(user?.id)

  const handleUpdateRole = async (m: Member, role: Role) => {
    setBusyId(String(m.id))
    setError('')
    try {
      await userApi.updateMember(String(m.id), { role })
      await refresh()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '操作失败')
    } finally {
      setBusyId(null)
    }
  }

  const handleToggleActive = async (m: Member) => {
    setBusyId(String(m.id))
    setError('')
    try {
      await userApi.updateMember(String(m.id), { isActive: m.isActive === 1 ? 0 : 1 })
      await refresh()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '操作失败')
    } finally {
      setBusyId(null)
    }
  }

  const handleRemove = async (m: Member) => {
    if (!window.confirm(`确定移除成员「${m.name}」？该成员将从列表中移除（历史数据保留）。`)) return
    setBusyId(String(m.id))
    setError('')
    try {
      await userApi.removeMember(String(m.id))
      await refresh()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '移除失败')
    } finally {
      setBusyId(null)
    }
  }

  const openInvite = () => {
    setShowInvite(true)
    setInviteName('')
    setInviteEmail('')
    setInviteRole('member')
    setInviteError('')
    setInviteResult(null)
    setCopied(false)
  }

  const handleInvite = async () => {
    setInviteLoading(true)
    setInviteError('')
    try {
      const result = await userApi.inviteMember(inviteName, inviteEmail, inviteRole)
      setInviteResult(result)
    } catch (err: unknown) {
      setInviteError(err instanceof Error ? err.message : '生成邀请链接失败')
    } finally {
      setInviteLoading(false)
    }
  }

  const copyLink = async () => {
    if (!inviteResult) return
    try {
      await navigator.clipboard.writeText(inviteResult.inviteUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setInviteError('复制失败，请手动复制')
    }
  }

  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">成员管理</h3>
        <button
          onClick={openInvite}
          className="px-4 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg flex items-center gap-2 shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          邀请成员
        </button>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {error && (
          <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>
        )}

        {/* 成员列表 */}
        <div className="bg-white rounded-xl border border-emerald-100 overflow-hidden mb-6">
          <table className="w-full">
            <thead className="bg-emerald-50/50 border-b border-emerald-100">
              <tr>
                {['成员', '邮箱', '角色', '状态', '加入时间', '操作'].map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-50">
              {loading ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-sm text-slate-400">
                    加载中…
                  </td>
                </tr>
              ) : members.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-sm text-slate-400">
                    暂无成员
                  </td>
                </tr>
              ) : (
                members.map((m) => {
                  const self = isSelf(m)
                  const busy = busyId === String(m.id)
                  return (
                    <tr key={m.id} className="hover:bg-emerald-50/40 transition">
                      <td className="px-4 py-3.5">
                        <div className="flex items-center gap-3">
                          <div className="w-9 h-9 rounded-full bg-gradient-to-br from-brand-500 to-brand-600 flex items-center justify-center text-white text-sm font-bold">
                            {m.name?.[0] ?? '?'}
                          </div>
                          <span className="text-sm text-slate-800 font-medium">{m.name}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3.5 text-sm text-slate-600">{m.email}</td>
                      <td className="px-4 py-3.5">
                        <span className={`px-2.5 py-0.5 rounded-full text-xs font-semibold ${ROLE_BADGE[m.role]}`}>
                          {ROLE_LABELS[m.role]}
                        </span>
                      </td>
                      <td className="px-4 py-3.5">
                        {m.isActive === 1 ? (
                          <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700">
                            启用
                          </span>
                        ) : (
                          <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-100 text-slate-500">
                            停用
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3.5 text-sm text-slate-500">{formatDate(m.createTime)}</td>
                      <td className="px-4 py-3.5">
                        <div className="flex items-center gap-1 flex-wrap">
                          {self ? (
                            <span className="text-xs text-slate-400">当前账户</span>
                          ) : (
                            <>
                              {m.role !== 'tenant_admin' ? (
                                <button
                                  disabled={busy}
                                  onClick={() => handleUpdateRole(m, 'tenant_admin')}
                                  className="px-2.5 py-1 rounded-md bg-emerald-50 text-brand-700 text-xs font-semibold hover:bg-emerald-100 transition disabled:opacity-50"
                                >
                                  设为管理员
                                </button>
                              ) : (
                                <button
                                  disabled={busy}
                                  onClick={() => handleUpdateRole(m, 'member')}
                                  className="px-2.5 py-1 rounded-md bg-slate-50 text-slate-600 text-xs font-semibold hover:bg-slate-100 transition disabled:opacity-50"
                                >
                                  设为成员
                                </button>
                              )}
                              <button
                                disabled={busy}
                                onClick={() => handleToggleActive(m)}
                                className="px-2.5 py-1 rounded-md bg-amber-50 text-amber-700 text-xs font-semibold hover:bg-amber-100 transition disabled:opacity-50"
                              >
                                {m.isActive === 1 ? '停用' : '启用'}
                              </button>
                              <button
                                disabled={busy}
                                onClick={() => handleRemove(m)}
                                className="px-2.5 py-1 rounded-md bg-red-50 text-red-600 text-xs font-semibold hover:bg-red-100 transition disabled:opacity-50"
                              >
                                移除
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* 邀请成员弹窗 */}
      {showInvite && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={() => setShowInvite(false)}>
          <div
            className="bg-white rounded-2xl w-full max-w-md p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-4">
              <h4 className="text-lg font-bold text-slate-800">邀请成员</h4>
              <button onClick={() => setShowInvite(false)} className="text-slate-400 hover:text-slate-600">
                <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            {inviteResult ? (
              <div>
                <p className="text-sm text-slate-600 mb-3">
                  邀请链接已生成，复制后发送给对方（7 天内有效，可多人复用）：
                </p>
                <div className="flex items-center gap-2 mb-3">
                  <input
                    readOnly
                    value={inviteResult.inviteUrl}
                    className="flex-1 px-3 py-2 border border-emerald-100 rounded-lg text-xs text-slate-600 bg-slate-50"
                  />
                  <button
                    onClick={copyLink}
                    className="px-3 py-2 rounded-lg bg-brand-600 text-white text-xs font-semibold hover:opacity-90 transition"
                  >
                    {copied ? '已复制' : '复制'}
                  </button>
                </div>
                <button
                  onClick={openInvite}
                  className="text-sm text-brand-600 hover:text-brand-700 font-medium"
                >
                  再邀请一位
                </button>
              </div>
            ) : (
              <div className="space-y-4">
                {inviteError && (
                  <div className="px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{inviteError}</div>
                )}
                <div>
                  <label className="block text-sm font-medium text-slate-600 mb-2">姓名</label>
                  <input
                    value={inviteName}
                    onChange={(e) => setInviteName(e.target.value)}
                    placeholder="被邀请人姓名"
                    className="input-field rounded-xl w-full px-4 py-3 outline-none text-sm"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-600 mb-2">邮箱</label>
                  <input
                    type="email"
                    value={inviteEmail}
                    onChange={(e) => setInviteEmail(e.target.value)}
                    placeholder="invitee@company.com"
                    className="input-field rounded-xl w-full px-4 py-3 outline-none text-sm"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-600 mb-2">角色</label>
                  <div className="flex gap-2">
                    {(['member', 'tenant_admin'] as InviteRole[]).map((r) => (
                      <button
                        key={r}
                        onClick={() => setInviteRole(r)}
                        className={`flex-1 px-3 py-2 rounded-lg text-sm font-medium border transition ${
                          inviteRole === r
                            ? 'border-brand-500 bg-emerald-50 text-brand-700'
                            : 'border-emerald-100 text-slate-500 hover:bg-emerald-50/50'
                        }`}
                      >
                        {ROLE_LABELS[r]}
                      </button>
                    ))}
                  </div>
                </div>
                <button
                  onClick={handleInvite}
                  disabled={inviteLoading || !inviteName.trim() || !inviteEmail.trim()}
                  className="w-full py-3 rounded-xl bg-gradient-to-r from-brand-600 to-brand-500 text-white font-semibold text-sm hover:opacity-90 transition disabled:opacity-50"
                >
                  {inviteLoading ? '生成中…' : '生成邀请链接'}
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
