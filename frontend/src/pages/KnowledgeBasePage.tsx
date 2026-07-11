/** 知识库管理页 — 按设计稿 03-knowledge-base.html 重写，保留后端 API 调用。*/

import { useCallback, useEffect, useRef, useState } from 'react'
import { knowledgeApi } from '@/api/knowledge'
import type { Document, KnowledgeBase } from '@/types'

const STATUS_CONFIG: Record<string, { text: string; cls: string; icon: string }> = {
  ready: { text: '已就绪', cls: 'bg-emerald-50 text-emerald-700', icon: 'M16.704 4.153a.75.75 0 0 1 .143 1.052l-8 10.5a.75.75 0 0 1-1.127.075l-4.5-4.5a.75.75 0 0 1 1.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 0 1 1.05-.143Z' },
  pending: { text: '待处理', cls: 'bg-slate-100 text-slate-500', icon: '' },
  parsing: { text: '解析中', cls: 'bg-amber-50 text-amber-700', icon: 'M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99' },
  embedding: { text: '向量化中', cls: 'bg-amber-50 text-amber-700', icon: 'M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99' },
  failed: { text: '解析失败', cls: 'bg-red-50 text-red-700', icon: 'M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z' },
}

const FILE_ICONS: Record<string, string> = {
  pdf: 'text-red-500',
  docx: 'text-blue-500',
  doc: 'text-blue-500',
  md: 'text-slate-500',
  txt: 'text-slate-500',
  xlsx: 'text-green-500',
  xls: 'text-green-500',
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function formatTime(ts: string | number): string {
  const d = new Date(typeof ts === 'number' ? ts : Date.parse(ts))
  if (isNaN(d.getTime())) return '-'
  return d.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
}

export default function KnowledgeBasePage() {
  const [tab, setTab] = useState<'shared' | 'personal'>('shared')
  const [kbList, setKbList] = useState<KnowledgeBase[]>([])
  const [selectedKb, setSelectedKb] = useState<KnowledgeBase | null>(null)
  const [documents, setDocuments] = useState<Document[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [newKbName, setNewKbName] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  const loadKbList = useCallback(async (scope: 'shared' | 'personal') => {
    setLoading(true)
    setError('')
    try {
      const list = await knowledgeApi.list(scope)
      setKbList(list || [])
      if (list && list.length > 0) setSelectedKb(list[0])
      else setSelectedKb(null)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  const loadDocuments = useCallback(async (kbId: string) => {
    try {
      const list = await knowledgeApi.listDocuments(kbId)
      setDocuments(list || [])
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '加载文档失败')
    }
  }, [])

  useEffect(() => { loadKbList(tab) }, [tab, loadKbList])
  useEffect(() => {
    if (selectedKb) loadDocuments(selectedKb.id)
    else setDocuments([])
  }, [selectedKb, loadDocuments])

  const handleTabChange = (newTab: 'shared' | 'personal') => {
    setTab(newTab)
    setKbList([])
    setSelectedKb(null)
    setDocuments([])
  }

  const handleCreate = async () => {
    if (!newKbName.trim()) return
    try {
      await knowledgeApi.add({ name: newKbName.trim(), scope: tab })
      setShowCreate(false)
      setNewKbName('')
      await loadKbList(tab)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '创建失败')
    }
  }

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !selectedKb) return
    setError('')
    try {
      await knowledgeApi.uploadDocument(selectedKb.id, file)
      await loadDocuments(selectedKb.id)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '上传失败')
    } finally {
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  const handleDelete = async (docId: string) => {
    if (!confirm('确认删除该文档？')) return
    try {
      await knowledgeApi.deleteDocument(docId)
      if (selectedKb) await loadDocuments(selectedKb.id)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6 flex-shrink-0">
        <div className="flex items-center gap-3">
          <h3 className="text-base font-bold text-slate-800">知识库管理</h3>
          {kbList.length > 0 && (
            <select
              value={selectedKb?.id || ''}
              onChange={(e) => {
                const kb = kbList.find((k) => k.id === e.target.value)
                if (kb) setSelectedKb(kb)
              }}
              className="px-3 py-1.5 rounded-lg border border-emerald-200 text-sm text-slate-600 bg-white cursor-pointer"
            >
              {kbList.map((kb) => (
                <option key={kb.id} value={kb.id}>{kb.name} ({kb.documentCount} 文档)</option>
              ))}
            </select>
          )}
        </div>
        <button
          onClick={() => kbList.length === 0 ? setShowCreate(true) : fileInputRef.current?.click()}
          disabled={!selectedKb && kbList.length > 0}
          className="px-4 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg flex items-center gap-2 shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition disabled:opacity-50"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          {kbList.length === 0 ? '新建知识库' : '上传文档'}
        </button>
        <input ref={fileInputRef} type="file" className="hidden" onChange={handleUpload} accept=".pdf,.docx,.md,.txt" />
      </div>

      <div className="flex-1 overflow-auto p-6">
        {error && <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>}

        {/* Tab 切换 */}
        <div className="flex items-center gap-1 mb-5 bg-emerald-50/50 p-1 rounded-xl w-fit border border-emerald-100">
          <button
            onClick={() => handleTabChange('shared')}
            className={`px-5 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 cursor-pointer transition ${tab === 'shared' ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500'}`}
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M7.217 10.907a2.25 2.25 0 1 0 0 2.186m0-2.186c.18.324.283.696.283 1.093s-.103.77-.283 1.093m0-2.186 9.566-5.314m-9.566 7.5 9.566 5.314m0 0a2.25 2.25 0 1 0 3.935 2.186 2.25 2.25 0 0 0-3.935-2.186Zm0-12.814a2.25 2.25 0 1 0 3.933-2.185 2.25 2.25 0 0 0-3.933 2.185Z" />
            </svg>
            共享知识库
            <span className="px-1.5 py-0.5 rounded-full bg-emerald-100 text-brand-600 text-[10px] font-bold">{tab === 'shared' ? kbList.length : ''}</span>
          </button>
          <button
            onClick={() => handleTabChange('personal')}
            className={`px-5 py-2 rounded-lg text-sm font-medium flex items-center gap-2 cursor-pointer transition ${tab === 'personal' ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500'}`}
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 12 21.75c-2.676 0-5.216-.584-7.499-1.632Z" />
            </svg>
            我的知识库
            <span className="px-1.5 py-0.5 rounded-full bg-slate-100 text-slate-500 text-[10px] font-bold">{tab === 'personal' ? kbList.length : ''}</span>
          </button>
        </div>

        {kbList.length === 0 ? (
          /* 空状态 */
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <div className="w-16 h-16 rounded-2xl bg-emerald-50 flex items-center justify-center mb-4">
              <svg className="w-8 h-8 text-brand-400" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25" />
              </svg>
            </div>
            <p className="text-slate-500 text-sm mb-4">暂无{tab === 'shared' ? '共享' : '个人'}知识库</p>
            <button onClick={() => setShowCreate(true)} className="px-4 py-2 rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold">新建知识库</button>
          </div>
        ) : (
          <>
            {/* 上传区 */}
            <div
              onClick={() => fileInputRef.current?.click()}
              className="border-2 border-dashed border-emerald-200 rounded-2xl p-8 text-center mb-6 bg-emerald-50/30 hover:border-brand-400 hover:bg-emerald-50/50 transition cursor-pointer"
            >
              <div className="w-16 h-16 rounded-2xl bg-brand-100 mx-auto mb-4 flex items-center justify-center">
                <svg className="w-8 h-8 text-brand-600" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 16.5m0 0L7.5 12m4.5 4.5V3" />
                </svg>
              </div>
              <div className="text-brand-700 font-semibold text-base mb-1">拖拽文件到此处，或点击选择文件</div>
              <div className="text-slate-500 text-sm">支持 PDF / Word / Markdown / TXT · 单文件最大 50MB</div>
            </div>

            {/* 文档表格 */}
            <div className="bg-white rounded-xl border border-emerald-100 overflow-hidden">
              <table className="w-full">
                <thead className="bg-emerald-50/50 border-b border-emerald-100">
                  <tr>
                    {['文件名', '类型', '大小', '分块', '状态', '上传时间', '操作'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-emerald-50">
                  {documents.length === 0 && (
                    <tr><td colSpan={7} className="px-4 py-8 text-center text-slate-400 text-sm">暂无文档，点击上方区域上传</td></tr>
                  )}
                  {documents.map((doc) => {
                    const st = STATUS_CONFIG[doc.status] || STATUS_CONFIG.pending
                    const iconCls = FILE_ICONS[doc.fileType] || 'text-slate-500'
                    return (
                      <tr key={doc.id} className="hover:bg-emerald-50/40 transition">
                        <td className="px-4 py-3.5 text-sm text-slate-800">
                          <div className="flex items-center gap-2">
                            <svg className={`w-4 h-4 ${iconCls}`} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
                            </svg>
                            {doc.filename}
                          </div>
                        </td>
                        <td className="px-4 py-3.5 text-sm text-slate-600 uppercase">{doc.fileType}</td>
                        <td className="px-4 py-3.5 text-sm text-slate-600">{formatSize(doc.fileSize)}</td>
                        <td className="px-4 py-3.5 text-sm text-slate-600">{doc.chunkCount > 0 ? doc.chunkCount : '—'}</td>
                        <td className="px-4 py-3.5">
                          <span className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold ${st.cls}`}>
                            {st.icon && (
                              <svg className={`w-3 h-3 ${doc.status === 'parsing' || doc.status === 'embedding' ? 'animate-spin' : ''}`} fill={doc.status === 'ready' || doc.status === 'failed' ? 'currentColor' : 'none'} stroke={doc.status === 'ready' || doc.status === 'failed' ? 'none' : 'currentColor'} strokeWidth="2" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" d={st.icon} />
                              </svg>
                            )}
                            {st.text}
                          </span>
                        </td>
                        <td className="px-4 py-3.5 text-sm text-slate-500">{formatTime(doc.createTime)}</td>
                        <td className="px-4 py-3.5">
                          <button onClick={() => handleDelete(doc.id)} className="px-2.5 py-1 rounded-md bg-red-50 text-red-600 text-xs font-semibold hover:bg-red-100 transition">删除</button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>

      {/* 新建知识库弹窗 */}
      {showCreate && (
        <div className="fixed inset-0 bg-black/30 flex items-center justify-center z-50" onClick={() => setShowCreate(false)}>
          <div className="bg-white rounded-2xl p-6 w-96 shadow-xl" onClick={(e) => e.stopPropagation()}>
            <h4 className="text-base font-bold text-slate-800 mb-4">新建知识库</h4>
            <input
              value={newKbName}
              onChange={(e) => setNewKbName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              placeholder="知识库名称"
              className="w-full px-4 py-3 rounded-xl border border-emerald-200 bg-white text-slate-800 text-sm mb-4"
              autoFocus
            />
            <div className="flex gap-3 justify-end">
              <button onClick={() => setShowCreate(false)} className="px-4 py-2 rounded-lg text-slate-500 text-sm hover:bg-slate-50">取消</button>
              <button onClick={handleCreate} className="px-4 py-2 rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-medium">创建</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
