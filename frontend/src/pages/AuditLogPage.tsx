/** 审计日志页 — 按 06-audit-log.html 设计稿重写。*/

import { Fragment, useState } from 'react'

const TAG_STYLES: Record<string, string> = {
  '文档上传': 'bg-emerald-50 text-brand-700',
  '文档删除': 'bg-red-50 text-red-600',
  'AI模型配置': 'bg-violet-50 text-violet-600',
  '成员变更': 'bg-emerald-50 text-emerald-600',
  '权限调整': 'bg-amber-50 text-amber-600',
}

interface LogEntry {
  time: string
  operator: string
  action: string
  target: string
  ip: string
  detail?: Record<string, unknown>
}

const LOGS: LogEntry[] = [
  { time: '2026-07-10 14:32:05', operator: '张三', action: '文档上传', target: '新员工入职手册.pdf', ip: '192.168.1.100', detail: { action: 'document_upload', filename: '新员工入职手册.pdf', size: '5.6MB', chunks: 142, result: 'success' } },
  { time: '2026-07-10 11:20:18', operator: '张三', action: 'AI模型配置', target: 'LLM → 火山方舟·豆包Pro', ip: '192.168.1.100' },
  { time: '2026-07-09 16:45:33', operator: '张三', action: '文档删除', target: '旧版考勤制度_2024.docx', ip: '192.168.1.100' },
  { time: '2026-07-08 09:15:42', operator: '张三', action: '成员变更', target: '赵六 加入租户 (普通成员)', ip: '192.168.1.100' },
  { time: '2026-07-07 14:00:12', operator: '张三', action: '权限调整', target: '李四: 普通成员 → 租户管理员', ip: '192.168.1.100' },
]

function JsonDetail({ data }: { data: Record<string, unknown> }) {
  const text = JSON.stringify(data, null, 2)
  return (
    <pre className="bg-slate-800 text-slate-300 p-4 rounded-lg text-xs font-mono leading-relaxed overflow-auto">
      {text}
    </pre>
  )
}

export default function AuditLogPage() {
  const [expanded, setExpanded] = useState<number | null>(0)

  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">审计日志</h3>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {/* 筛选栏 */}
        <div className="flex gap-3 mb-4">
          <select className="px-4 py-2.5 border border-emerald-200 rounded-lg text-sm bg-white text-slate-600 cursor-pointer">
            <option>全部操作类型</option>
            <option>文档上传</option>
            <option>文档删除</option>
            <option>成员变更</option>
            <option>AI模型配置</option>
          </select>
          <select className="px-4 py-2.5 border border-emerald-200 rounded-lg text-sm bg-white text-slate-600 cursor-pointer">
            <option>全部时间</option>
            <option>今天</option>
            <option>最近7天</option>
          </select>
          <select className="px-4 py-2.5 border border-emerald-200 rounded-lg text-sm bg-white text-slate-600 cursor-pointer">
            <option>全部操作人</option>
            <option>张三</option>
            <option>李四</option>
          </select>
        </div>

        {/* 日志表格 */}
        <div className="bg-white rounded-xl border border-emerald-100 overflow-hidden">
          <table className="w-full">
            <thead className="bg-emerald-50/50 border-b border-emerald-100">
              <tr>
                {['时间', '操作人', '操作类型', '目标对象', 'IP地址', '详情'].map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-50">
              {LOGS.map((log, idx) => {
                const isOpen = expanded === idx
                const hasDetail = !!log.detail
                return (
                  <Fragment key={idx}>
                    <tr className="hover:bg-emerald-50/40 transition">
                      <td className="px-4 py-3.5 text-sm text-slate-600">{log.time}</td>
                      <td className="px-4 py-3.5 text-sm text-slate-800 font-medium">{log.operator}</td>
                      <td className="px-4 py-3.5">
                        <span className={`px-2.5 py-0.5 rounded-full text-xs font-semibold ${TAG_STYLES[log.action] || 'bg-slate-50 text-slate-600'}`}>{log.action}</span>
                      </td>
                      <td className="px-4 py-3.5 text-sm text-slate-600">{log.target}</td>
                      <td className="px-4 py-3.5 text-sm text-slate-400 font-mono">{log.ip}</td>
                      <td className="px-4 py-3.5">
                        {hasDetail ? (
                          <button
                            onClick={() => setExpanded(isOpen ? null : idx)}
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
                    {isOpen && hasDetail && (
                      <tr>
                        <td colSpan={6} className="bg-emerald-50/30 px-6 py-4">
                          <JsonDetail data={log.detail!} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
