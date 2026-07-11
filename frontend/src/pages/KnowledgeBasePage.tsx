/** 知识库管理页骨架。*/

import { useState } from 'react'

export default function KnowledgeBasePage() {
  const [tab, setTab] = useState<'shared' | 'personal'>('shared')

  return (
    <div className="flex flex-col h-full">
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center px-6">
        <h3 className="text-base font-bold text-slate-800">知识库管理</h3>
      </div>
      <div className="flex-1 overflow-auto p-6">
        {/* Tab 切换 */}
        <div className="flex items-center gap-1 mb-5 bg-emerald-50/50 p-1 rounded-xl w-fit border border-emerald-100">
          <button
            onClick={() => setTab('shared')}
            className={`px-5 py-2 rounded-lg text-sm font-semibold transition ${
              tab === 'shared' ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-500'
            }`}
          >
            📁 共享知识库 <span className="ml-1 text-xs">12</span>
          </button>
          <button
            onClick={() => setTab('personal')}
            className={`px-5 py-2 rounded-lg text-sm font-medium transition ${
              tab === 'personal' ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-500'
            }`}
          >
            👤 我的知识库 <span className="ml-1 text-xs">3</span>
          </button>
        </div>

        {/* 上传区 */}
        <div className="mb-6 border-2 border-dashed border-emerald-200 rounded-2xl p-8 text-center hover:border-brand-400 transition cursor-pointer">
          <div className="text-4xl mb-3">📎</div>
          <p className="text-slate-600 font-medium">拖拽文件到此处上传</p>
          <p className="text-slate-400 text-sm mt-1">支持 PDF / Word / Markdown / TXT</p>
        </div>

        {/* 文档列表占位 */}
        <div className="bg-white rounded-2xl border border-emerald-100 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-emerald-50/50 text-slate-500">
              <tr>
                <th className="text-left px-4 py-3 font-medium">文件名</th>
                <th className="text-left px-4 py-3 font-medium">类型</th>
                <th className="text-left px-4 py-3 font-medium">大小</th>
                <th className="text-left px-4 py-3 font-medium">状态</th>
                <th className="text-left px-4 py-3 font-medium">上传时间</th>
                <th className="text-left px-4 py-3 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr className="border-t border-emerald-50">
                <td className="px-4 py-3 text-slate-700">员工手册.pdf</td>
                <td className="px-4 py-3 text-slate-500">PDF</td>
                <td className="px-4 py-3 text-slate-500">2.4 MB</td>
                <td className="px-4 py-3"><span className="px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-600 text-xs">就绪</span></td>
                <td className="px-4 py-3 text-slate-400">2026-07-11</td>
                <td className="px-4 py-3 text-slate-400">—</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
