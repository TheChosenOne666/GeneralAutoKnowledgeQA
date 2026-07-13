/** 审计日志页 — 对接真实审计接口：筛选（操作类型 / 操作人 / 时间范围）+ 分页 + JSON 详情展开。*/

import { useEffect, useState } from 'react'
import { auditApi } from '@/api/audit'
import type { AuditLog } from '@/types'

/** 操作类型展示元信息（后端 action 码 → 中文标签 + 徽章样式）。*/
const ACTION_META: Record<string, { label: string; cls: string }> = {
  login: { label: '登录', cls: 'bg-blue-50 text-blue-600' },
  logout: { label: '登出', cls: 'bg-slate-100 text-slate-600' },
  doc_upload: { label: '文档上传', cls: 'bg-emerald-50 text-brand-700' },
  doc_delete: { label: '文档删除', cls: 'bg-red-50 text-red-600' },
  config_update: { label: 'AI模型配置', cls: 'bg-violet-50 text-violet-600' },
  member_change: { label: '成员变更', cls: 'bg-amber-50 text-amber-600' },
}

const ACTION_OPTIONS = [
  { value: '', label: '全部操作类型' },
  ...Object.entries(ACTION_META).map(([value, m]) => ({ value, label: m.label })),
]

const RANGE_OPTIONS = [
  { value: 'all', label: '全部时间', days: 0 },
  { value: 'today', label: '今天', days: 0, today: true },
  { value: '7', label: '近7天', days: 7 },
  { value: '30', label: '近30天', days: 30 },
  { value: '90', label: '近90天', days: 90 },
]

const PAGE_SIZE = 10

/** yyyy-MM-dd。*/
function toDateStr(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 按时间范围计算起始日期（含），返回 yyyy-MM-dd。*/
function rangeStart(range: string): string | undefined {
  const opt = RANGE_OPTIONS.find((o) => o.value === range)
  if (!opt || opt.value === 'all') return undefined
  const now = new Date()
  if (opt.today) return toDateStr(now)
  const start = new Date(now.getTime() - opt.days * 86_400_000)
  return toDateStr(start)
}

function formatDateTime(value?: string): string {
  if (!value) return '—'
  const d = new Date(value)
  if (isNaN(d.getTime())) return value
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function JsonDetail({ raw }: { raw: string | null }) {
  if (!raw) return <span className="text-slate-300 text-xs">—</span>
  let text = raw
  try {
    text = JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    // 非标准 JSON 时保留原始文本
  }
  return (
    <pre className="bg-slate-800 text-slate-300 p-4 rounded-lg text-xs font-mono leading-relaxed overflow-auto whitespace-pre-wrap">
      {text}
    </pre>
  )
}

export default function AuditLogPage() {
  const [logs, setLogs] = useState<AuditLog[]>([])
  const [total, setTotal] = useState(0)
  const [current, setCurrent] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  // 筛选条件
  const [action, setAction] = useState('')
  const [userEmail, setUserEmail] = useState('')
  const [range, setRange] = useState('all')

  const [expandedId, setExpandedId] = useState<string | null>(null)

  const fetchLogs = async (page: number) => {
    setLoading(true)
    setError('')
    try {
      const data = await auditApi.list({
        action: action || undefined,
        userEmail: userEmail.trim() || undefined,
        startTime: rangeStart(range),
        current: page,
        pageSize: PAGE_SIZE,
      })
      setLogs(data.records ?? [])
      setTotal(data.total ?? 0)
      setCurrent(data.current ?? page)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '加载审计日志失败')
    } finally {
      setLoading(false)
    }
  }

  // 筛选条件变化时回到第 1 页
  useEffect(() => {
    fetchLogs(1)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [action, userEmail, range])

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const handleReset = () => {
    setAction('')
    setUserEmail('')
    setRange('all')
  }

  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">审计日志</h3>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {error && (
          <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>
        )}

        {/* 筛选栏 */}
        <div className="flex flex-wrap gap-3 mb-4 items-center">
          <select
            value={action}
            onChange={(e) => setAction(e.target.value)}
            className="px-4 py-2.5 border border-emerald-200 rounded-lg text-sm bg-white text-slate-600 cursor-pointer"
          >
            {ACTION_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
          <input
            value={userEmail}
            onChange={(e) => setUserEmail(e.target.value)}
            placeholder="操作人邮箱"
            className="px-4 py-2.5 border border-emerald-200 rounded-lg text-sm bg-white text-slate-600 outline-none focus:border-brand-400 w-56"
          />
          <select
            value={range}
            onChange={(e) => setRange(e.target.value)}
            className="px-4 py-2.5 border border-emerald-200 rounded-lg text-sm bg-white text-slate-600 cursor-pointer"
          >
            {RANGE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
          <button
            onClick={handleReset}
            className="px-4 py-2.5 rounded-lg border border-emerald-200 text-sm text-slate-500 hover:bg-emerald-50 transition"
          >
            重置
          </button>
        </div>

        {/* 日志表格 */}
        <div className="bg-white rounded-xl border border-emerald-100 overflow-hidden">
          <table className="w-full">
            <thead className="bg-emerald-50/50 border-b border-emerald-100">
              <tr>
                {['时间', '操作人', '操作类型', '资源类型', 'IP地址', '详情'].map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-50">
              {loading ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-sm text-slate-400">加载中…</td>
                </tr>
              ) : logs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-sm text-slate-400">暂无审计记录</td>
                </tr>
              ) : (
                logs.map((log) => {
                  const isOpen = expandedId === log.id
                  const hasDetail = !!log.detail
                  const meta = ACTION_META[log.action] ?? { label: log.action, cls: 'bg-slate-50 text-slate-600' }
                  return (
                    <tr key={log.id} className="hover:bg-emerald-50/40 transition">
                      <td className="px-4 py-3.5 text-sm text-slate-600 whitespace-nowrap">{formatDateTime(log.createTime)}</td>
                      <td className="px-4 py-3.5 text-sm text-slate-800 font-medium">{log.userEmail ?? '—'}</td>
                      <td className="px-4 py-3.5">
                        <span className={`px-2.5 py-0.5 rounded-full text-xs font-semibold ${meta.cls}`}>{meta.label}</span>
                      </td>
                      <td className="px-4 py-3.5 text-sm text-slate-600">{log.resourceType ?? '—'}</td>
                      <td className="px-4 py-3.5 text-sm text-slate-400 font-mono">{log.ipAddress ?? '—'}</td>
                      <td className="px-4 py-3.5">
                        {hasDetail ? (
                          <button
                            onClick={() => setExpandedId(isOpen ? null : log.id)}
                            className="px-2.5 py-1 rounded-md bg-emerald-50 text-brand-700 text-xs font-semibold hover:bg-emerald-100 transition inline-flex items-center gap-1"
                          >
                            {isOpen ? '收起' : '展开'}
                            <svg className={`w-3 h-3 transition-transform ${isOpen ? 'rotate-180' : ''}`} fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                            </svg>
                          </button>
                        ) : <span className="text-slate-300 text-xs">—</span>}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>

        {/* 分页 */}
        {!loading && logs.length > 0 && (
          <div className="flex items-center justify-between mt-4 text-sm text-slate-500">
            <span>共 {total} 条</span>
            <div className="flex items-center gap-2">
              <button
                disabled={current <= 1}
                onClick={() => fetchLogs(current - 1)}
                className="px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 hover:bg-emerald-50 disabled:opacity-40 disabled:cursor-not-allowed transition"
              >
                上一页
              </button>
              <span>{current} / {totalPages}</span>
              <button
                disabled={current >= totalPages}
                onClick={() => fetchLogs(current + 1)}
                className="px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 hover:bg-emerald-50 disabled:opacity-40 disabled:cursor-not-allowed transition"
              >
                下一页
              </button>
            </div>
          </div>
        )}

        {/* 展开详情 */}
        {expandedId && logs.find((l) => l.id === expandedId) && (
          <div className="mt-4">
            <JsonDetail raw={logs.find((l) => l.id === expandedId)!.detail} />
          </div>
        )}
      </div>
    </div>
  )
}
