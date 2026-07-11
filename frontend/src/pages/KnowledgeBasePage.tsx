/** 知识库管理页 — 接入后端 API：列表、创建、文档上传、文档删除。*/

import { useCallback, useEffect, useRef, useState } from 'react'
import { knowledgeApi } from '@/api/knowledge'
import type { Document, KnowledgeBase } from '@/types'

const STATUS_LABELS: Record<string, { text: string; cls: string }> = {
  pending: { text: '待处理', cls: 'bg-slate-100 text-slate-500' },
  parsing: { text: '解析中', cls: 'bg-amber-100 text-amber-600' },
  embedding: { text: '向量化', cls: 'bg-amber-100 text-amber-600' },
  ready: { text: '就绪', cls: 'bg-emerald-100 text-emerald-600' },
  failed: { text: '失败', cls: 'bg-red-100 text-red-600' },
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function formatTime(ts: string | number): string {
  const d = new Date(typeof ts === 'number' ? ts : Date.parse(ts))
  return isNaN(d.getTime()) ? '-' : d.toLocaleString('zh-CN', { hour12: false })
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

  /** 加载知识库列表。*/
  const loadKbList = useCallback(async (scope: 'shared' | 'personal') => {
    setLoading(true)
    setError('')
    try {
      const list = await knowledgeApi.list(scope)
      setKbList(list || [])
      if (list && list.length > 0 && !selectedKb) {
        setSelectedKb(list[0])
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [selectedKb])

  /** 加载文档列表。*/
  const loadDocuments = useCallback(async (kbId: string) => {
    try {
      const list = await knowledgeApi.listDocuments(kbId)
      setDocuments(list || [])
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '加载文档失败')
    }
  }, [])

  useEffect(() => {
    loadKbList(tab)
  }, [tab, loadKbList])

  useEffect(() => {
    if (selectedKb) {
      loadDocuments(selectedKb.id)
    } else {
      setDocuments([])
    }
  }, [selectedKb, loadDocuments])

  /** 切换 Tab。*/
  const handleTabChange = (newTab: 'shared' | 'personal') => {
    setTab(newTab)
    setSelectedKb(null)
    setKbList([])
  }

  /** 创建知识库。*/
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

  /** 上传文档。*/
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

  /** 删除文档。*/
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
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6">
        <h3 className="text-base font-bold text-slate-800">知识库管理</h3>
        <button
          onClick={() => setShowCreate(true)}
          className="px-4 py-2 rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-medium hover:shadow-lg transition"
        >
          + 新建知识库
        </button>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {error && (
          <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>
        )}

        {/* Tab 切换 */}
        <div className="flex items-center gap-1 mb-5 bg-emerald-50/50 p-1 rounded-xl w-fit border border-emerald-100">
          <button
            onClick={() => handleTabChange('shared')}
            className={`px-5 py-2 rounded-lg text-sm font-semibold transition ${
              tab === 'shared' ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-500'
            }`}
          >
            📁 共享知识库 <span className="ml-1 text-xs">{tab === 'shared' ? kbList.length : ''}</span>
          </button>
          <button
            onClick={() => handleTabChange('personal')}
            className={`px-5 py-2 rounded-lg text-sm font-medium transition ${
              tab === 'personal' ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-500'
            }`}
          >
            👤 我的知识库 <span className="ml-1 text-xs">{tab === 'personal' ? kbList.length : ''}</span>
          </button>
        </div>

        <div className="flex gap-5">
          {/* 知识库列表 */}
          <div className="w-64 shrink-0 space-y-2">
            {loading && <p className="text-slate-400 text-sm">加载中...</p>}
            {!loading && kbList.length === 0 && (
              <p className="text-slate-400 text-sm py-4 text-center">暂无知识库</p>
            )}
            {kbList.map((kb) => (
              <button
                key={kb.id}
                onClick={() => setSelectedKb(kb)}
                className={`w-full text-left px-4 py-3 rounded-xl border transition ${
                  selectedKb?.id === kb.id
                    ? 'border-brand-400 bg-emerald-50 text-emerald-700'
                    : 'border-emerald-100 bg-white text-slate-700 hover:border-brand-200'
                }`}
              >
                <div className="font-medium text-sm truncate">{kb.name}</div>
                <div className="text-xs text-slate-400 mt-1">{kb.documentCount} 个文档</div>
              </button>
            ))}
          </div>

          {/* 文档列表 */}
          <div className="flex-1 min-w-0">
            {selectedKb ? (
              <>
                {/* 上传区 */}
                <div
                  onClick={() => fileInputRef.current?.click()}
                  className="mb-4 border-2 border-dashed border-emerald-200 rounded-2xl p-6 text-center hover:border-brand-400 transition cursor-pointer"
                >
                  <div className="text-3xl mb-2">📎</div>
                  <p className="text-slate-600 font-medium text-sm">点击上传文件</p>
                  <p className="text-slate-400 text-xs mt-1">支持 PDF / Word / Markdown / TXT</p>
                  <input
                    ref={fileInputRef}
                    type="file"
                    className="hidden"
                    onChange={handleUpload}
                    accept=".pdf,.docx,.md,.txt"
                  />
                </div>

                {/* 文档表格 */}
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
                      {documents.length === 0 && (
                        <tr>
                          <td colSpan={6} className="px-4 py-8 text-center text-slate-400">
                            暂无文档，点击上方区域上传
                          </td>
                        </tr>
                      )}
                      {documents.map((doc) => {
                        const status = STATUS_LABELS[doc.status] || STATUS_LABELS.pending
                        return (
                          <tr key={doc.id} className="border-t border-emerald-50">
                            <td className="px-4 py-3 text-slate-700">{doc.filename}</td>
                            <td className="px-4 py-3 text-slate-500 uppercase">{doc.fileType}</td>
                            <td className="px-4 py-3 text-slate-500">{formatSize(doc.fileSize)}</td>
                            <td className="px-4 py-3">
                              <span className={`px-2 py-0.5 rounded-full text-xs ${status.cls}`}>{status.text}</span>
                            </td>
                            <td className="px-4 py-3 text-slate-400">{formatTime(doc.createTime)}</td>
                            <td className="px-4 py-3">
                              <button
                                onClick={() => handleDelete(doc.id)}
                                className="text-red-400 hover:text-red-600 text-xs font-medium"
                              >
                                删除
                              </button>
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            ) : (
              <div className="flex items-center justify-center h-64 text-slate-400 text-sm">
                请从左侧选择一个知识库
              </div>
            )}
          </div>
        </div>
      </div>

      {/* 新建知识库弹窗 */}
      {showCreate && (
        <div
          className="fixed inset-0 bg-black/30 flex items-center justify-center z-50"
          onClick={() => setShowCreate(false)}
        >
          <div
            className="bg-white rounded-2xl p-6 w-96 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
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
              <button
                onClick={() => setShowCreate(false)}
                className="px-4 py-2 rounded-lg text-slate-500 text-sm hover:bg-slate-50"
              >
                取消
              </button>
              <button
                onClick={handleCreate}
                className="px-4 py-2 rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-medium"
              >
                创建
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
