/** 知识库管理页 — 按设计稿 03-knowledge-base.html 重写，保留后端 API 调用。*/

import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { knowledgeApi } from '@/api/knowledge'
import { useAuth } from '@/hooks/useAuth'
import type { Document, KnowledgeBase } from '@/types'

const STATUS_CONFIG: Record<string, { text: string; cls: string; icon: string }> = {
  ready: { text: '已就绪', cls: 'bg-emerald-50 text-emerald-700', icon: 'M16.704 4.153a.75.75 0 0 1 .143 1.052l-8 10.5a.75.75 0 0 1-1.127.075l-4.5-4.5a.75.75 0 0 1 1.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 0 1 1.05-.143Z' },
  processing: { text: '处理中', cls: 'bg-slate-100 text-slate-500', icon: 'M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99' },
  parsing: { text: '解析中', cls: 'bg-amber-50 text-amber-700', icon: 'M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99' },
  retrieving: { text: '检索中', cls: 'bg-amber-50 text-amber-700', icon: 'M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99' },
  optimizing: { text: '优化中', cls: 'bg-violet-50 text-violet-700', icon: 'M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99' },
  failed: { text: '处理失败', cls: 'bg-red-50 text-red-700', icon: 'M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z' },
  cancelled: { text: '已取消', cls: 'bg-slate-100 text-slate-400', icon: 'M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z' },
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

/** 批量上传弹窗中待上传的本地文件（尚未解析）。*/
interface UploadFileItem {
  /** 前端临时唯一标识。*/
  uid: string
  file: File
  name: string
  size: number
  /** 文件扩展名（不含点），小写。*/
  ext: string
}

export default function KnowledgeBasePage() {
  const [tab, setTab] = useState<'shared' | 'personal'>('shared')
  const [kbList, setKbList] = useState<KnowledgeBase[]>([])
  const [selectedKb, setSelectedKb] = useState<KnowledgeBase | null>(null)
  const [documents, setDocuments] = useState<Document[]>([])
  const [, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [newKbName, setNewKbName] = useState('')

  // 批量上传弹窗：从本地选入文件列表，确认后统一走完整解析流程
  const [showBatchUpload, setShowBatchUpload] = useState(false)
  const [uploadFiles, setUploadFiles] = useState<UploadFileItem[]>([])
  const [uploading, setUploading] = useState(false)
  // 拖拽悬停高亮
  const [dragActive, setDragActive] = useState(false)
  // 批量上传进度：当前第几个 / 共几个
  const [uploadBatch, setUploadBatch] = useState<{ current: number; total: number } | null>(null)
  const [uploadProgress, setUploadProgress] = useState<number | null>(null)

  // 批量删除弹窗：弹窗内列表勾选
  const [showBatchDelete, setShowBatchDelete] = useState(false)
  const [deleteIds, setDeleteIds] = useState<Set<string>>(new Set())

  const uploadFileInputRef = useRef<HTMLInputElement>(null)
  // 文档内容查看弹窗
  const [viewingDoc, setViewingDoc] = useState<Document | null>(null)
  const [viewContent, setViewContent] = useState('')
  const [viewLoading, setViewLoading] = useState(false)
  const [viewError, setViewError] = useState('')
  const navigate = useNavigate()
  const { user } = useAuth()
  const isAdmin = user?.role === 'tenant_admin' || user?.role === 'super_admin'
  // 共享库仅租户管理员 / 平台超管可维护；个人库仅展示本人库，全员可维护
  const canWrite = tab === 'personal' ? true : isAdmin

  // 是否存在因 AI 模型配置错误（API Key/模型名/向量维度填错）导致处理失败的文档
  const hasModelConfigError = documents.some((d) => d.modelConfigError)
  // 是否存在因模型额度不足 / 被限流（HTTP 429 / 5xx 过载 / 余额耗尽）导致处理失败的文档
  const hasQuotaError = documents.some((d) => d.quotaError)
  // 额度受限文档中可重试的第一个（终态 failed）
  const firstQuotaDoc = documents.find((d) => d.quotaError && d.status === 'failed')

  const loadKbList = useCallback(async (scope: 'shared' | 'personal') => {
    setLoading(true)
    setError('')
    try {
      const list = await knowledgeApi.list(scope)
      setKbList(list || [])
      // 刷新时保留当前选中的知识库（用于上传/删除后同步文档数而不丢失选择）
      setSelectedKb((prev) => {
        if (prev && list && list.some((k) => k.id === prev.id)) return prev
        return list && list.length > 0 ? list[0] : null
      })
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

  // 文档处于处理中（待处理/解析中/向量化中）时自动轮询刷新状态，避免界面“一动不动”
  useEffect(() => {
    if (!selectedKb) return
    const processing = documents.some(
      (d) => d.status === 'processing' || d.status === 'parsing' || d.status === 'retrieving' || d.status === 'optimizing'
    )
    if (!processing) return
    const timer = setInterval(() => loadDocuments(selectedKb.id), 3000)
    return () => clearInterval(timer)
  }, [documents, selectedKb, loadDocuments])

  useEffect(() => { loadKbList(tab) }, [tab, loadKbList])
  useEffect(() => {
    if (selectedKb) loadDocuments(selectedKb.id)
    else setDocuments([])
    // 切换知识库时清空批量删除选中，避免跨库残留
    setDeleteIds(new Set())
  }, [selectedKb, loadDocuments])

  const handleTabChange = (newTab: 'shared' | 'personal') => {
    setTab(newTab)
    setKbList([])
    setSelectedKb(null)
    setDocuments([])
    setDeleteIds(new Set())
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

  // 批量上传弹窗：把本地文件加入列表（暂不解析）
  const addUploadFiles = (files: File[]) => {
    if (files.length === 0) return
    const added: UploadFileItem[] = files.map((f) => ({
      uid: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
      file: f,
      name: f.name,
      size: f.size,
      ext: (f.name.split('.').pop() || '').toLowerCase(),
    }))
    setUploadFiles((prev) => [...prev, ...added])
  }

  // 点击选择文件
  const handlePickUploadFiles = (e: React.ChangeEvent<HTMLInputElement>) => {
    addUploadFiles(Array.from(e.target.files || []))
    if (uploadFileInputRef.current) uploadFileInputRef.current.value = ''
  }

  // 拖拽放入文件
  const handleDropUploadFiles = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setDragActive(false)
    if (uploading) return
    addUploadFiles(Array.from(e.dataTransfer.files || []))
  }

  // 从待上传列表中移除某文件
  const handleRemoveUploadFile = (uid: string) => {
    setUploadFiles((prev) => prev.filter((f) => f.uid !== uid))
  }

  // 批量上传确认：用户选完本地文件后，一次性统一走完整上传+解析流程
  const handleConfirmBatchUpload = async () => {
    if (uploadFiles.length === 0 || !selectedKb || uploading) return
    setUploading(true)
    setError('')
    const total = uploadFiles.length
    const failedNames: string[] = []
    // 逐个上传：单个失败不阻断其余文件，最后统一提示与刷新
    for (let i = 0; i < total; i++) {
      setUploadBatch({ current: i + 1, total })
      setUploadProgress(0)
      try {
        await knowledgeApi.uploadDocument(selectedKb.id, uploadFiles[i].file, (pct) => setUploadProgress(pct))
      } catch (err: unknown) {
        failedNames.push(uploadFiles[i].name)
        // 记录但继续；错误详情见汇总提示
        void err
      }
    }
    setUploadProgress(null)
    setUploadBatch(null)
    setUploading(false)
    // 清空列表并关闭弹窗
    setUploadFiles([])
    setShowBatchUpload(false)
    if (failedNames.length > 0) {
      setError(`${failedNames.length} 个文件上传失败：${failedNames.join('、')}`)
    }
    await loadDocuments(selectedKb.id)
    await loadKbList(tab) // 同步知识库文档数
  }

  const handleDelete = async (docId: string) => {
    if (!confirm('确认删除该文档？')) return
    try {
      await knowledgeApi.deleteDocument(docId)
      if (selectedKb) await loadDocuments(selectedKb.id)
      await loadKbList(tab) // 同步知识库文档数
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  // 批量删除：弹窗内确认后统一删除选中文档
  const handleBatchDelete = async () => {
    if (deleteIds.size === 0) return
    if (!confirm(`确认删除选中的 ${deleteIds.size} 个文档？`)) return
    try {
      await knowledgeApi.batchDeleteDocuments(Array.from(deleteIds))
      setDeleteIds(new Set())
      setShowBatchDelete(false)
      if (selectedKb) await loadDocuments(selectedKb.id)
      await loadKbList(tab) // 同步知识库文档数
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '批量删除失败')
    }
  }

  // 批量删除弹窗内的勾选切换
  const toggleDeleteSelect = (docId: string) => {
    setDeleteIds((prev) => {
      const next = new Set(prev)
      if (next.has(docId)) next.delete(docId)
      else next.add(docId)
      return next
    })
  }

  const handleCancel = async (docId: string) => {
    try {
      await knowledgeApi.cancelDocument(docId)
      if (selectedKb) await loadDocuments(selectedKb.id)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '取消失败')
    }
  }

  const handleRetry = async (docId: string) => {
    try {
      await knowledgeApi.retryDocument(docId)
      if (selectedKb) await loadDocuments(selectedKb.id)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '重试失败')
    }
  }

  // 查看已就绪/优化中文档的全文内容（优化中向量已入库，已可检索）
  const handleView = async (doc: Document) => {
    if (doc.status !== 'ready' && doc.status !== 'optimizing') return
    setViewingDoc(doc)
    setViewContent('')
    setViewError('')
    setViewLoading(true)
    try {
      const content = await knowledgeApi.getDocumentContent(doc.id)
      setViewContent(content || '（文档内容为空）')
    } catch (err: unknown) {
      setViewError(err instanceof Error ? err.message : '读取内容失败')
    } finally {
      setViewLoading(false)
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
        {canWrite && (
          <div className="flex items-center gap-2">
            <button
              onClick={() => setShowCreate(true)}
              className="px-4 py-2 border border-emerald-200 text-slate-600 text-sm font-semibold rounded-lg flex items-center gap-2 hover:bg-emerald-50 transition"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
              </svg>
              新建知识库
            </button>
            <button
              onClick={() => selectedKb ? setShowBatchUpload(true) : alert('请先选择或创建知识库')}
              className="px-4 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg flex items-center gap-2 shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 16.5m0 0L7.5 12m4.5 4.5V3" />
              </svg>
              批量上传
            </button>
            <button
              onClick={() => selectedKb ? setShowBatchDelete(true) : alert('请先选择或创建知识库')}
              className="px-4 py-2 border border-red-200 text-red-600 text-sm font-semibold rounded-lg flex items-center gap-2 hover:bg-red-50 transition"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0" />
              </svg>
              批量删除
            </button>
          </div>
        )}
        <input ref={uploadFileInputRef} type="file" multiple className="hidden" onChange={handlePickUploadFiles} accept=".pdf,.docx,.md,.txt" />
      </div>

      <div className="flex-1 overflow-auto p-6">
        {error && <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>}

        {/* 模型配置错误提示：文档向量化失败因 API Key/模型名/向量维度填错 */}
        {hasModelConfigError && (
          <div className="mb-4 flex items-start gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3">
            <svg className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
            </svg>
            <div className="flex-1 text-sm text-red-800 leading-relaxed">
              <span className="font-semibold">模型配置不正确</span>，部分文档向量化失败，请检查 Embedding 的 API Key、模型名或向量维度后重新配置，并重新上传。
            </div>
            <button
              onClick={() => navigate('/ai-config')}
              className="flex-shrink-0 px-3 py-1.5 rounded-lg bg-red-500 text-white text-xs font-semibold hover:bg-red-600 transition"
            >
              去配置
            </button>
          </div>
        )}

        {/* 模型额度 / 限流提示：文档向量化失败因额度不足或被限流（HTTP 429 等），
            与「模型配置错误」区分——此处应稍后重试 / 检查账户额度，而非去重配模型 */}
        {hasQuotaError && (
          <div className="mb-4 flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
            <svg className="w-5 h-5 text-amber-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
            </svg>
            <div className="flex-1 text-sm text-amber-800 leading-relaxed">
              <span className="font-semibold">模型额度不足或被限流</span>，部分文档向量化失败（如 HTTP 429 / 服务端繁忙 / 账户余额耗尽）。这通常不是配置错误，请稍后重试，或检查该模型的账户额度 / 套餐后重新上传。
            </div>
            {firstQuotaDoc && (
              <button
                onClick={() => handleRetry(firstQuotaDoc.id)}
                className="flex-shrink-0 px-3 py-1.5 rounded-lg bg-amber-500 text-white text-xs font-semibold hover:bg-amber-600 transition"
              >
                重试
              </button>
            )}
          </div>
        )}

        {/* 共享库只读提示：普通成员仅可浏览与问答，无法上传/删除 */}
        {tab === 'shared' && !isAdmin && (
          <div className="mb-4 flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">
            <svg className="w-4 h-4 text-slate-400 flex-shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-6.75a2.25 2.25 0 0 0-2.25-2.25H6.75a2.25 2.25 0 0 0-2.25 2.25v6.75a2.25 2.25 0 0 0 2.25 2.25Z" />
            </svg>
            只读模式：共享知识库仅租户管理员可维护，你当前可浏览与问答，但无法上传或删除文档。
          </div>
        )}

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
            {canWrite && (
              <button onClick={() => setShowCreate(true)} className="px-4 py-2 rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold">新建知识库</button>
            )}
          </div>
        ) : (
          <>
            {/* 上传进度条（批量上传确认后显示） */}
            {uploadProgress !== null && (
              <div className="mb-6 rounded-xl border border-brand-200 bg-brand-50/40 px-4 py-3">
                <div className="flex items-center justify-between text-sm mb-2">
                  <span className="text-brand-700 font-medium">
                    正在上传文档…{uploadBatch && uploadBatch.total > 1 ? `（${uploadBatch.current}/${uploadBatch.total}）` : ''}
                  </span>
                  <span className="text-brand-600 font-semibold">{uploadProgress}%</span>
                </div>
                <div className="h-2 w-full rounded-full bg-brand-100 overflow-hidden">
                  <div
                    className="h-full bg-gradient-to-r from-brand-600 to-brand-500 transition-all duration-200"
                    style={{ width: `${uploadProgress}%` }}
                  />
                </div>
              </div>
            )}

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
                  {documents.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="px-4 py-12">
                        <div className="flex flex-col items-center justify-center text-center">
                          <div className="w-14 h-14 rounded-2xl bg-emerald-50 flex items-center justify-center mb-3">
                            <svg className="w-7 h-7 text-brand-400" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
                            </svg>
                          </div>
                          <p className="text-slate-500 text-sm">该知识库暂无文档</p>
                          {canWrite && <p className="text-slate-400 text-xs mt-1">点击右上角「批量上传」添加 PDF / Word / Markdown / TXT</p>}
                        </div>
                      </td>
                    </tr>
                  ) : (
                    documents.map((doc) => {
                    const st = STATUS_CONFIG[doc.status] || STATUS_CONFIG.pending
                    const iconCls = FILE_ICONS[doc.fileType] || 'text-slate-500'
                    return (
                      <tr key={doc.id} className="hover:bg-emerald-50/40 transition">
                        <td className="px-4 py-3.5 text-sm text-slate-800">
                          <div className="flex items-center gap-2">
                            <svg className={`w-4 h-4 ${iconCls}`} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
                            </svg>
                            {doc.status === 'ready' || doc.status === 'optimizing' ? (
                              <button
                                onClick={() => handleView(doc)}
                                className="text-left text-slate-800 hover:text-brand-600 hover:underline transition"
                                title="查看文档内容"
                              >
                                {doc.filename}
                              </button>
                            ) : (
                              <span>{doc.filename}</span>
                            )}
                          </div>
                        </td>
                        <td className="px-4 py-3.5 text-sm text-slate-600 uppercase">{doc.fileType}</td>
                        <td className="px-4 py-3.5 text-sm text-slate-600">{formatSize(doc.fileSize)}</td>
                        <td className="px-4 py-3.5 text-sm text-slate-600">{doc.chunkCount > 0 ? doc.chunkCount : '—'}</td>
                        <td className="px-4 py-3.5">
                          <span className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold ${st.cls}`}>
                            {st.icon && (
                              <svg className={`w-3 h-3 ${doc.status === 'parsing' || doc.status === 'retrieving' || doc.status === 'optimizing' ? 'animate-spin' : ''}`} fill={doc.status === 'ready' || doc.status === 'failed' ? 'currentColor' : 'none'} stroke={doc.status === 'ready' || doc.status === 'failed' ? 'none' : 'currentColor'} strokeWidth="2" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" d={st.icon} />
                              </svg>
                            )}
                            {st.text}
                          </span>
                          {doc.status === 'optimizing' && (
                            <div className="mt-0.5 text-[10px] text-violet-400">已可检索</div>
                          )}
                        </td>
                        <td className="px-4 py-3.5 text-sm text-slate-500">{formatTime(doc.createTime)}</td>
                        <td className="px-4 py-3.5">
                          {canWrite && (
                            <div className="flex items-center gap-2">
                              {doc.status !== 'ready' && doc.status !== 'failed' && doc.status !== 'cancelled' && (
                                <button
                                  onClick={() => handleCancel(doc.id)}
                                  className="px-2.5 py-1 rounded-md bg-amber-50 text-amber-600 text-xs font-semibold hover:bg-amber-100 transition"
                                >
                                  取消
                                </button>
                              )}
                              {(doc.status === 'failed' || doc.status === 'cancelled') && (
                                <button
                                  onClick={() => handleRetry(doc.id)}
                                  className="px-2.5 py-1 rounded-md bg-brand-50 text-brand-600 text-xs font-semibold hover:bg-brand-100 transition"
                                >
                                  重试
                                </button>
                              )}
                              <button
                                onClick={() => handleDelete(doc.id)}
                                className="px-2.5 py-1 rounded-md bg-red-50 text-red-600 text-xs font-semibold hover:bg-red-100 transition"
                              >
                                删除
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    )
                  }))}
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

      {/* 文档内容查看弹窗 */}
      {viewingDoc && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={() => setViewingDoc(null)}>
          <div
            className="bg-white rounded-2xl shadow-xl w-full max-w-3xl max-h-[80vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-6 py-4 border-b border-emerald-100 flex-shrink-0">
              <div className="min-w-0">
                <h4 className="text-base font-bold text-slate-800 truncate">{viewingDoc.filename}</h4>
                <p className="text-xs text-slate-400 mt-0.5">文档内容预览</p>
              </div>
              <button
                onClick={() => setViewingDoc(null)}
                className="ml-4 flex-shrink-0 w-8 h-8 rounded-lg flex items-center justify-center text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition"
                aria-label="关闭"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            <div className="flex-1 overflow-auto px-6 py-4">
              {viewLoading ? (
                <div className="flex items-center justify-center py-16 text-slate-400 text-sm">正在加载文档内容…</div>
              ) : viewError ? (
                <div className="px-4 py-3 rounded-lg bg-red-50 text-red-600 text-sm">{viewError}</div>
              ) : (
                <div className="text-sm leading-relaxed text-slate-700 font-sans">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{viewContent}</ReactMarkdown>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 批量上传弹窗：选择本地文件入列表，确认后统一走完整解析流程 */}
      {showBatchUpload && (
        <div className="fixed inset-0 bg-black/30 flex items-center justify-center z-50 p-4" onClick={() => !uploading && setShowBatchUpload(false)}>
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[85vh] flex flex-col" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-6 py-4 border-b border-emerald-100 flex-shrink-0">
              <div>
                <h4 className="text-base font-bold text-slate-800">批量上传文档</h4>
                <p className="text-xs text-slate-400 mt-0.5">支持 PDF / Word / Markdown / TXT · 选完文件后点击「确认上传」统一解析</p>
              </div>
              <button
                onClick={() => !uploading && setShowBatchUpload(false)}
                disabled={uploading}
                className="w-8 h-8 rounded-lg flex items-center justify-center text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition disabled:opacity-40"
                aria-label="关闭"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <div className="flex-1 overflow-auto px-6 py-4">
              {/* 选择文件区（点击或拖拽均可） */}
              <div
                onClick={() => !uploading && uploadFileInputRef.current?.click()}
                onDragOver={(e) => { e.preventDefault(); if (!uploading) setDragActive(true) }}
                onDragLeave={() => setDragActive(false)}
                onDrop={handleDropUploadFiles}
                className={`border-2 border-dashed rounded-2xl p-6 text-center transition cursor-pointer ${dragActive ? 'border-brand-500 bg-brand-50/60' : 'border-emerald-200 bg-emerald-50/30 hover:border-brand-400 hover:bg-emerald-50/50'}`}
              >
                <div className="w-12 h-12 rounded-2xl bg-brand-100 mx-auto mb-3 flex items-center justify-center">
                  <svg className="w-6 h-6 text-brand-600" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 16.5m0 0L7.5 12m4.5 4.5V3" />
                  </svg>
                </div>
                <div className="text-brand-700 font-semibold text-sm mb-1">点击选择文件，或将文件拖拽到此处（可多次添加）</div>
                <div className="text-slate-500 text-xs">已选 {uploadFiles.length} 个文件</div>
              </div>

              {/* 待上传文件列表 */}
              {uploadFiles.length > 0 && (
                <div className="mt-4 rounded-xl border border-emerald-100 overflow-hidden">
                  <table className="w-full">
                    <tbody className="divide-y divide-emerald-50">
                      {uploadFiles.map((f) => {
                        const iconCls = FILE_ICONS[f.ext] || 'text-slate-500'
                        return (
                          <tr key={f.uid} className="hover:bg-emerald-50/40 transition">
                            <td className="px-4 py-3 text-sm text-slate-800">
                              <div className="flex items-center gap-2">
                                <svg className={`w-4 h-4 ${iconCls}`} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                                  <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
                                </svg>
                                <span className="truncate">{f.name}</span>
                              </div>
                            </td>
                            <td className="px-4 py-3 text-sm text-slate-500 uppercase w-16">{f.ext}</td>
                            <td className="px-4 py-3 text-sm text-slate-500 w-24">{formatSize(f.size)}</td>
                            <td className="px-4 py-3 text-right w-10">
                              <button
                                onClick={() => handleRemoveUploadFile(f.uid)}
                                disabled={uploading}
                                className="px-2 py-1 rounded-md bg-red-50 text-red-600 text-xs font-semibold hover:bg-red-100 transition disabled:opacity-40"
                              >
                                移除
                              </button>
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="flex items-center justify-between gap-3 px-6 py-4 border-t border-emerald-100 flex-shrink-0">
              <span className="text-sm text-slate-400">{uploadFiles.length > 0 ? `共 ${uploadFiles.length} 个文件` : '尚未选择文件'}</span>
              <div className="flex gap-3">
                <button onClick={() => !uploading && setShowBatchUpload(false)} disabled={uploading} className="px-4 py-2 rounded-lg text-slate-500 text-sm hover:bg-slate-50 disabled:opacity-40">取消</button>
                <button
                  onClick={handleConfirmBatchUpload}
                  disabled={uploadFiles.length === 0 || uploading}
                  className="px-4 py-2 rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-medium shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition disabled:opacity-40"
                >
                  {uploading ? '上传中…' : '确认上传'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 批量删除弹窗：列表勾选后统一删除 */}
      {showBatchDelete && (
        <div className="fixed inset-0 bg-black/30 flex items-center justify-center z-50 p-4" onClick={() => setShowBatchDelete(false)}>
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[85vh] flex flex-col" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-6 py-4 border-b border-emerald-100 flex-shrink-0">
              <div>
                <h4 className="text-base font-bold text-slate-800">批量删除文档</h4>
                <p className="text-xs text-slate-400 mt-0.5">勾选需要删除的文档，确认后统一移除</p>
              </div>
              <button
                onClick={() => setShowBatchDelete(false)}
                className="w-8 h-8 rounded-lg flex items-center justify-center text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition"
                aria-label="关闭"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <div className="flex-1 overflow-auto px-6 py-4">
              {documents.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-center">
                  <p className="text-slate-500 text-sm">该知识库暂无文档</p>
                </div>
              ) : (
                <>
                  {documents.length > 0 && (
                    <div className="mb-2 flex items-center justify-between text-xs text-slate-500">
                      <span>已选 {deleteIds.size} / {documents.length} 个</span>
                      <button
                        onClick={() => setDeleteIds(deleteIds.size === documents.length ? new Set() : new Set(documents.map((d) => d.id)))}
                        className="px-2 py-1 rounded-md text-brand-600 hover:bg-emerald-50 transition"
                      >
                        {deleteIds.size === documents.length ? '取消全选' : '全选'}
                      </button>
                    </div>
                  )}
                  <div className="rounded-xl border border-emerald-100 overflow-hidden">
                    <table className="w-full">
                      <tbody className="divide-y divide-emerald-50">
                        {documents.map((doc) => {
                          const iconCls = FILE_ICONS[doc.fileType] || 'text-slate-500'
                          return (
                            <tr key={doc.id} className="hover:bg-emerald-50/40 transition">
                              <td className="px-4 py-3 w-10">
                                <input
                                  type="checkbox"
                                  checked={deleteIds.has(doc.id)}
                                  onChange={() => toggleDeleteSelect(doc.id)}
                                  className="w-4 h-4 rounded border-emerald-300 text-brand-600 focus:ring-brand-500 cursor-pointer"
                                  aria-label={`选择 ${doc.filename}`}
                                />
                              </td>
                              <td className="px-4 py-3 text-sm text-slate-800">
                                <div className="flex items-center gap-2">
                                  <svg className={`w-4 h-4 ${iconCls}`} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
                                  </svg>
                                  <span className="truncate">{doc.filename}</span>
                                </div>
                              </td>
                              <td className="px-4 py-3 text-sm text-slate-500 uppercase w-16">{doc.fileType}</td>
                              <td className="px-4 py-3 text-sm text-slate-500">{formatSize(doc.fileSize)}</td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                </>
              )}
            </div>

            <div className="flex items-center justify-between gap-3 px-6 py-4 border-t border-emerald-100 flex-shrink-0">
              <span className="text-sm text-slate-400">已选 {deleteIds.size} 个文档</span>
              <div className="flex gap-3">
                <button onClick={() => setShowBatchDelete(false)} className="px-4 py-2 rounded-lg text-slate-500 text-sm hover:bg-slate-50">取消</button>
                <button
                  onClick={handleBatchDelete}
                  disabled={deleteIds.size === 0}
                  className="px-4 py-2 rounded-lg bg-red-500 text-white text-sm font-medium hover:bg-red-600 transition disabled:opacity-40"
                >
                  确认删除
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
