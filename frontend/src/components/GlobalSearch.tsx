/**
 * 全局搜索组件 — 侧边栏搜索入口。
 *
 * 搜索范围：
 * - 文档 chunk：ES BM25（content + source 多字段）+ 向量 kNN 召回 + RRF 融合
 * - 聊天消息：ES BM25（content + conversation_title 多字段）
 * - 会话标题 / 知识库名称：前端本地过滤
 *
 * 功能：
 * - 搜索运算符："精确短语"、-排除、+必含
 * - 防抖 300ms
 * - Tab 分组（全部/会话/消息/文档/知识库）
 * - 键盘导航（↑↓ Enter Esc）
 * - 高亮片段
 * - 搜索历史（localStorage，最多 10 条）
 * - 加载更多（分页 from += topK）
 * - 语义搜索开关
 */

import { useState, useRef, useEffect, useCallback, useMemo } from 'react'
import { searchApi, type DocSearchResult, type MsgSearchResult } from '@/api/search'
import type { Conversation, KnowledgeBase } from '@/types'

interface GlobalSearchProps {
  conversations: Conversation[]
  knowledgeBases: KnowledgeBase[]
  onSelectConversation: (id: string) => void
  onSelectKnowledgeBase: (kbId: string) => void
  onSelectDocument: (doc: { docId: string; kbId: string; source: string; query: string }) => void
  onSelectMessage: (msg: { conversationId: string; conversationTitle: string }) => void
}

type Tab = 'all' | 'conversations' | 'messages' | 'documents' | 'knowledgeBases'

interface FlatItem {
  id: string
  type: 'conversation' | 'message' | 'document' | 'kb'
  onClick: () => void
}

const HISTORY_KEY = 'xiongda_search_history'
const MAX_HISTORY = 10
const PAGE_SIZE = 10

/** 安全渲染高亮（只允许 <em> 标签）。*/
function safeHighlight(html: string): string {
  // 只保留 <em> 和 </em>，转义其他 HTML
  return html
    .replace(/<(?!\/?em\b)[^>]*>/g, '')
    .replace(/&/g, '&amp;')
    .replace(/<(?!\/?em\b)/g, '&lt;')
    .replace(/(?<!em)>/g, '&gt;')
}

/** 读取搜索历史。*/
function loadHistory(): string[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

/** 保存搜索历史。*/
function saveHistory(query: string) {
  const q = query.trim()
  if (!q) return
  const history = loadHistory().filter((h) => h !== q)
  history.unshift(q)
  localStorage.setItem(HISTORY_KEY, JSON.stringify(history.slice(0, MAX_HISTORY)))
}

export function GlobalSearch({
  conversations,
  knowledgeBases,
  onSelectConversation,
  onSelectKnowledgeBase,
  onSelectDocument,
  onSelectMessage,
}: GlobalSearchProps) {
  const [query, setQuery] = useState('')
  const [activeTab, setActiveTab] = useState<Tab>('all')
  const [docs, setDocs] = useState<DocSearchResult[]>([])
  const [msgs, setMsgs] = useState<MsgSearchResult[]>([])
  const [totalDocs, setTotalDocs] = useState(0)
  const [totalMsgs, setTotalMsgs] = useState(0)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [focused, setFocused] = useState(false)
  const [highlightIndex, setHighlightIndex] = useState(-1)
  const [from, setFrom] = useState(0)
  const [enableSemantic, setEnableSemantic] = useState(true)
  const [history, setHistory] = useState<string[]>([])
  const containerRef = useRef<HTMLDivElement>(null)
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 本地过滤：会话标题 + 知识库名称
  const localConversations = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return []
    // 支持运算符：去掉 - 和 + 前缀和引号
    const cleanQ = q.replace(/["+\-]/g, ' ').trim()
    if (!cleanQ) return []
    return conversations.filter((c) => c.title.toLowerCase().includes(cleanQ)).slice(0, 8)
  }, [conversations, query])

  const localKbs = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return []
    const cleanQ = q.replace(/["+\-]/g, ' ').trim()
    if (!cleanQ) return []
    return knowledgeBases.filter((kb) => kb.name.toLowerCase().includes(cleanQ)).slice(0, 5)
  }, [knowledgeBases, query])

  // 搜索执行（防抖 300ms）
  const executeSearch = useCallback(async (q: string, fromOffset: number, append: boolean) => {
    if (!q.trim()) {
      setDocs([])
      setMsgs([])
      setTotalDocs(0)
      setTotalMsgs(0)
      setLoading(false)
      return
    }
    try {
      const remote = await searchApi.global(q.trim(), {
        topK: PAGE_SIZE,
        from: fromOffset,
        enableSemantic,
      })
      if (append) {
        setDocs((prev) => [...prev, ...(remote.documents || [])])
        setMsgs((prev) => [...prev, ...(remote.messages || [])])
      } else {
        setDocs(remote.documents || [])
        setMsgs(remote.messages || [])
      }
      setTotalDocs(remote.total_documents || 0)
      setTotalMsgs(remote.total_messages || 0)
    } catch {
      if (!append) {
        setDocs([])
        setMsgs([])
        setTotalDocs(0)
        setTotalMsgs(0)
      }
    } finally {
      setLoading(false)
      setLoadingMore(false)
    }
  }, [enableSemantic])

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value
    setQuery(val)
    setHighlightIndex(-1)
    setFrom(0)
    if (debounceTimer.current) clearTimeout(debounceTimer.current)
    if (!val.trim()) {
      setDocs([])
      setMsgs([])
      setTotalDocs(0)
      setTotalMsgs(0)
      setLoading(false)
      return
    }
    setLoading(true)
    debounceTimer.current = setTimeout(() => executeSearch(val, 0, false), 300)
  }

  const handleClear = () => {
    setQuery('')
    setDocs([])
    setMsgs([])
    setTotalDocs(0)
    setTotalMsgs(0)
    setLoading(false)
    setHighlightIndex(-1)
    setFrom(0)
  }

  // 加载更多
  const handleLoadMore = () => {
    const newFrom = from + PAGE_SIZE
    setFrom(newFrom)
    setLoadingMore(true)
    executeSearch(query, newFrom, true)
  }

  // 保存搜索历史
  const handleSearch = (q: string) => {
    if (q.trim()) {
      saveHistory(q.trim())
      setHistory(loadHistory())
    }
  }

  // 点击外部关闭
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setFocused(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  // 聚焦时加载历史
  useEffect(() => {
    if (focused) {
      setHistory(loadHistory())
    }
  }, [focused])

  // 检测是否包含搜索运算符
  const hasOperators = useMemo(() => {
    return /["+\-]/.test(query.trim())
  }, [query])

  const canLoadMoreDocs = docs.length < totalDocs
  const canLoadMoreMsgs = msgs.length < totalMsgs
  const canLoadMore = canLoadMoreDocs || canLoadMoreMsgs

  // 扁平化结果列表（用于键盘导航）
  const flatItems: FlatItem[] = useMemo(() => {
    const items: FlatItem[] = []
    const push = (type: Tab, list: unknown[], makeOnClick: (i: number) => () => void) => {
      if (activeTab === 'all' || activeTab === type) {
        list.forEach((_item, i) => items.push({ id: String(i), type: type.slice(0, 4) as FlatItem['type'], onClick: makeOnClick(i) }))
      }
    }
    push('conversations', localConversations, (i) => () => { onSelectConversation(localConversations[i].id); handleSearch(query); setFocused(false) })
    push('messages', msgs, (i) => () => { onSelectMessage({ conversationId: msgs[i].conversation_id, conversationTitle: msgs[i].conversation_title }); handleSearch(query); setFocused(false) })
    push('documents', docs, (i) => () => { onSelectDocument({ docId: docs[i].doc_id, kbId: docs[i].kb_id, source: docs[i].source, query: query.trim() }); handleSearch(query); setFocused(false) })
    push('knowledgeBases', localKbs, (i) => () => { onSelectKnowledgeBase(localKbs[i].id); handleSearch(query); setFocused(false) })
    return items
  }, [activeTab, localConversations, msgs, docs, localKbs, onSelectConversation, onSelectMessage, onSelectDocument, onSelectKnowledgeBase, query])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!focused) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setHighlightIndex((p) => (p + 1) % Math.max(flatItems.length, 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setHighlightIndex((p) => (p <= 0 ? flatItems.length - 1 : p - 1))
    } else if (e.key === 'Enter' && highlightIndex >= 0 && highlightIndex < flatItems.length) {
      e.preventDefault()
      flatItems[highlightIndex].onClick()
    } else if (e.key === 'Escape') {
      setFocused(false)
      ;(e.target as HTMLInputElement).blur()
    }
  }

  const hasResults =
    localConversations.length > 0 || msgs.length > 0 || docs.length > 0 || localKbs.length > 0
  const totalCount = localConversations.length + msgs.length + docs.length + localKbs.length

  const showConversations = (activeTab === 'all' || activeTab === 'conversations') && localConversations.length > 0
  const showMessages = (activeTab === 'all' || activeTab === 'messages') && msgs.length > 0
  const showDocuments = (activeTab === 'all' || activeTab === 'documents') && docs.length > 0
  const showKbs = (activeTab === 'all' || activeTab === 'knowledgeBases') && localKbs.length > 0

  let runningIndex = 0
  const convStart = runningIndex
  if (showConversations) runningIndex += localConversations.length
  const msgStart = runningIndex
  if (showMessages) runningIndex += msgs.length
  const docStart = runningIndex
  if (showDocuments) runningIndex += docs.length
  const kbStart = runningIndex

  const showHistory = focused && !query.trim() && history.length > 0

  return (
    <div ref={containerRef} className="relative">
      {/* 搜索框 */}
      <div className="relative">
        <svg className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z" />
        </svg>
        <input
          type="text"
          value={query}
          onChange={handleInputChange}
          onFocus={() => setFocused(true)}
          onKeyDown={handleKeyDown}
          placeholder="搜索文档、消息、会话..."
          className="w-full rounded-md border border-slate-200 bg-slate-50 pl-8 pr-7 py-1.5 text-xs text-slate-600 placeholder:text-slate-400 focus:outline-none focus:ring-1 focus:ring-emerald-400 focus:border-emerald-400 focus:bg-white transition"
        />
        {loading && (
          <div className="absolute right-2 top-1/2 -translate-y-1/2">
            <svg className="w-3.5 h-3.5 text-emerald-400 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 0 1 8-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </div>
        )}
        {!loading && query && (
          <button onClick={handleClear} className="absolute right-1.5 top-1/2 -translate-y-1/2 w-4 h-4 flex items-center justify-center text-slate-400 hover:text-slate-600 rounded transition" title="清除">
            <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
            </svg>
          </button>
        )}
      </div>

      {/* 结果面板 */}
      {focused && (query.trim() || showHistory) && (
        <div className="absolute left-0 right-0 top-full mt-1 bg-white rounded-lg border border-slate-200 shadow-lg max-h-[450px] overflow-y-auto z-50">
          {/* 搜索历史 */}
          {showHistory && (
            <div className="py-1">
              <div className="px-3 py-1 text-[10px] font-semibold text-slate-400 uppercase tracking-wide flex items-center justify-between">
                <span>搜索历史</span>
                {history.length > 0 && (
                  <button
                    onClick={() => { localStorage.removeItem(HISTORY_KEY); setHistory([]) }}
                    className="text-[10px] text-slate-300 hover:text-slate-500"
                  >
                    清除
                  </button>
                )}
              </div>
              {history.map((h, i) => (
                <button
                  key={i}
                  onClick={() => { setQuery(h); setFocused(true); setFrom(0); setLoading(true); executeSearch(h, 0, false) }}
                  className="w-full text-left px-3 py-1.5 flex items-center gap-2 hover:bg-slate-50 transition"
                >
                  <svg className="w-3 h-3 text-slate-300" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
                  </svg>
                  <span className="text-xs text-slate-600 truncate">{h}</span>
                </button>
              ))}
            </div>
          )}

          {/* 运算符提示 */}
          {hasOperators && (
            <div className="px-3 py-1.5 bg-amber-50 border-b border-amber-100 text-[10px] text-amber-600">
              💡 检测到搜索运算符：<code className="text-amber-700">"精确"</code> / <code className="text-amber-700">-排除</code> / <code className="text-amber-700">+必含</code>
            </div>
          )}

          {loading ? (
            <div className="px-3 py-6 text-center text-xs text-slate-400">
              <svg className="w-5 h-5 mx-auto mb-1.5 text-emerald-400 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 0 1 8-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              搜索中...
            </div>
          ) : !query.trim() ? null : !hasResults ? (
            <div className="px-3 py-6 text-center text-xs text-slate-400">未找到相关结果</div>
          ) : (
            <>
              {/* Tab 栏 + 语义搜索开关 */}
              <div className="flex items-center gap-1 px-2 py-1.5 border-b border-slate-100 sticky top-0 bg-white z-10">
                <TabBtn active={activeTab === 'all'} onClick={() => setActiveTab('all')} count={totalCount}>全部</TabBtn>
                {localConversations.length > 0 && <TabBtn active={activeTab === 'conversations'} onClick={() => setActiveTab('conversations')} count={localConversations.length}>会话</TabBtn>}
                {msgs.length > 0 && <TabBtn active={activeTab === 'messages'} onClick={() => setActiveTab('messages')} count={msgs.length}>消息</TabBtn>}
                {docs.length > 0 && <TabBtn active={activeTab === 'documents'} onClick={() => setActiveTab('documents')} count={docs.length}>文档</TabBtn>}
                {localKbs.length > 0 && <TabBtn active={activeTab === 'knowledgeBases'} onClick={() => setActiveTab('knowledgeBases')} count={localKbs.length}>知识库</TabBtn>}
                <div className="ml-auto flex items-center gap-1">
                  <label className="flex items-center gap-1 cursor-pointer" title="语义搜索：同时用向量召回，提升相关性">
                    <input
                      type="checkbox"
                      checked={enableSemantic}
                      onChange={(e) => {
                        setEnableSemantic(e.target.checked)
                        if (query.trim()) {
                          setLoading(true)
                          setFrom(0)
                          executeSearch(query, 0, false)
                        }
                      }}
                      className="w-2.5 h-2.5 accent-emerald-500"
                    />
                    <span className="text-[10px] text-slate-400">语义</span>
                  </label>
                </div>
              </div>

              <div className="py-1">
                {showConversations && (
                  <Section title="会话">
                    {localConversations.map((c, i) => (
                      <Item key={c.id} highlighted={highlightIndex === convStart + i} onClick={() => { onSelectConversation(c.id); handleSearch(query); setFocused(false) }}>
                        <span className="text-slate-400 text-xs">💬</span>
                        <span className="text-xs text-slate-700 truncate">{c.title}</span>
                      </Item>
                    ))}
                  </Section>
                )}
                {showMessages && (
                  <Section title="聊天消息" extra={totalMsgs > msgs.length ? `${msgs.length}/${totalMsgs}` : undefined}>
                    {msgs.map((m, i) => (
                      <Item key={m.id} highlighted={highlightIndex === msgStart + i} onClick={() => { onSelectMessage({ conversationId: m.conversation_id, conversationTitle: m.conversation_title }); handleSearch(query); setFocused(false) }}>
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="text-slate-400 text-xs">{m.role === 'user' ? '👤' : '🤖'}</span>
                            <span className="text-xs text-slate-400 truncate">{m.conversation_title}</span>
                          </div>
                          {m.highlight ? (
                            <span className="text-xs text-slate-600 truncate pl-5" dangerouslySetInnerHTML={{ __html: safeHighlight(m.highlight) }} />
                          ) : (
                            <span className="text-xs text-slate-600 truncate pl-5">{m.content}</span>
                          )}
                        </div>
                      </Item>
                    ))}
                  </Section>
                )}
                {showDocuments && (
                  <Section title="文档" extra={totalDocs > docs.length ? `${docs.length}/${totalDocs}` : undefined}>
                    {docs.map((d, i) => (
                      <Item key={d.doc_id + i} highlighted={highlightIndex === docStart + i} onClick={() => { onSelectDocument({ docId: d.doc_id, kbId: d.kb_id, source: d.source, query: query.trim() }); handleSearch(query); setFocused(false) }}>
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <span className="text-xs text-slate-700 truncate">📄 {d.source || d.doc_id}</span>
                          {d.highlight && <span className="text-[11px] text-slate-400 truncate pl-5" dangerouslySetInnerHTML={{ __html: safeHighlight(d.highlight) }} />}
                          {!d.highlight && d.content && <span className="text-[11px] text-slate-400 truncate pl-5">{d.content}</span>}
                        </div>
                      </Item>
                    ))}
                  </Section>
                )}
                {showKbs && (
                  <Section title="知识库">
                    {localKbs.map((kb, i) => (
                      <Item key={kb.id} highlighted={highlightIndex === kbStart + i} onClick={() => { onSelectKnowledgeBase(kb.id); handleSearch(query); setFocused(false) }}>
                        <span className="text-slate-400 text-xs">📚</span>
                        <span className="text-xs text-slate-700 truncate">{kb.name}</span>
                      </Item>
                    ))}
                  </Section>
                )}

                {/* 加载更多 */}
                {canLoadMore && (
                  <button
                    onClick={handleLoadMore}
                    disabled={loadingMore}
                    className="w-full text-center py-2 text-[11px] text-emerald-600 hover:bg-emerald-50 transition disabled:opacity-50"
                  >
                    {loadingMore ? '加载中...' : '加载更多'}
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}

function TabBtn({ active, onClick, count, children }: { active: boolean; onClick: () => void; count: number; children: React.ReactNode }) {
  return (
    <button onClick={onClick} className={`px-2 py-0.5 rounded text-[11px] font-medium transition ${active ? 'bg-emerald-50 text-emerald-600' : 'text-slate-400 hover:text-slate-600 hover:bg-slate-50'}`}>
      {children} <span className="text-slate-300">{count}</span>
    </button>
  )
}

function Section({ title, extra, children }: { title: string; extra?: string; children: React.ReactNode }) {
  return (
    <div className="mb-1">
      <div className="px-3 py-1 flex items-center justify-between">
        <span className="text-[10px] font-semibold text-slate-400 uppercase tracking-wide">{title}</span>
        {extra && <span className="text-[10px] text-slate-300">{extra}</span>}
      </div>
      {children}
    </div>
  )
}

function Item({ highlighted, onClick, children }: { highlighted: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick} className={`w-full text-left px-3 py-2 flex items-center gap-2 transition ${highlighted ? 'bg-emerald-50' : 'hover:bg-slate-50'}`}>
      {children}
    </button>
  )
}
