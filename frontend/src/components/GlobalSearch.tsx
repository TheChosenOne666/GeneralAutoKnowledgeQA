/**
 * 全局搜索组件 — 嵌入侧边栏，搜索范围：文档 chunk、聊天消息、知识库名称、会话标题。
 *
 * 搜索逻辑：
 * - 文档 chunk + 聊天消息 → 后端 /api/search/global（ES BM25，ES 不可用时 PG ILIKE 兜底）
 * - 知识库名称 / 会话标题 → 前端本地过滤（数据量小）
 *
 * 结果在搜索框下方分组展示，支持键盘导航（↑↓ 选择、Enter 确认、Esc 关闭）。
 */

import { useState, useRef, useEffect, useCallback, useMemo } from 'react'
import { searchApi, type DocSearchResult, type MsgSearchResult } from '@/api/search'
import type { Conversation, KnowledgeBase } from '@/types'

interface GlobalSearchProps {
  conversations: Conversation[]
  knowledgeBases: KnowledgeBase[]
  onSelectConversation: (id: string) => void
  onSelectKnowledgeBase: (kbId: string) => void
  onSelectDocument: (doc: { docId: string; kbId: string; source: string }) => void
  onSelectMessage: (msg: { conversationId: string; conversationTitle: string }) => void
}

type Tab = 'all' | 'conversations' | 'messages' | 'documents' | 'knowledgeBases'

interface FlatItem {
  id: string
  type: 'conversation' | 'message' | 'document' | 'kb'
  onClick: () => void
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
  const [loading, setLoading] = useState(false)
  const [focused, setFocused] = useState(false)
  const [highlightIndex, setHighlightIndex] = useState(-1)
  const containerRef = useRef<HTMLDivElement>(null)
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 本地过滤：会话标题 + 知识库名称
  const localConversations = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return []
    return conversations.filter((c) => c.title.toLowerCase().includes(q)).slice(0, 8)
  }, [conversations, query])

  const localKbs = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return []
    return knowledgeBases.filter((kb) => kb.name.toLowerCase().includes(q)).slice(0, 5)
  }, [knowledgeBases, query])

  // 搜索执行（防抖 300ms）
  const executeSearch = useCallback(async (q: string) => {
    if (!q.trim()) {
      setDocs([])
      setMsgs([])
      setLoading(false)
      return
    }
    try {
      const remote = await searchApi.global(q.trim())
      setDocs(remote.documents)
      setMsgs(remote.messages)
    } catch {
      setDocs([])
      setMsgs([])
    } finally {
      setLoading(false)
    }
  }, [])

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value
    setQuery(val)
    setHighlightIndex(-1)
    if (debounceTimer.current) clearTimeout(debounceTimer.current)
    if (!val.trim()) {
      setDocs([])
      setMsgs([])
      setLoading(false)
      return
    }
    setLoading(true)
    debounceTimer.current = setTimeout(() => executeSearch(val), 300)
  }

  const handleClear = () => {
    setQuery('')
    setDocs([])
    setMsgs([])
    setLoading(false)
    setHighlightIndex(-1)
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

  // 扁平化结果列表（用于键盘导航）
  const flatItems: FlatItem[] = useMemo(() => {
    const items: FlatItem[] = []
    const push = (type: Tab, list: unknown[], makeOnClick: (i: number) => () => void) => {
      if (activeTab === 'all' || activeTab === type) {
        list.forEach((_item, i) => items.push({ id: String(i), type: type.slice(0, 4) as FlatItem['type'], onClick: makeOnClick(i) }))
      }
    }
    push('conversations', localConversations, (i) => () => { onSelectConversation(localConversations[i].id); setFocused(false) })
    push('messages', msgs, (i) => () => { onSelectMessage({ conversationId: msgs[i].conversation_id, conversationTitle: msgs[i].conversation_title }); setFocused(false) })
    push('documents', docs, (i) => () => { onSelectDocument({ docId: docs[i].doc_id, kbId: docs[i].kb_id, source: docs[i].source }); setFocused(false) })
    push('knowledgeBases', localKbs, (i) => () => { onSelectKnowledgeBase(localKbs[i].id); setFocused(false) })
    return items
  }, [activeTab, localConversations, msgs, docs, localKbs, onSelectConversation, onSelectMessage, onSelectDocument, onSelectKnowledgeBase])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!focused || flatItems.length === 0) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setHighlightIndex((p) => (p + 1) % flatItems.length)
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

  // 按当前 tab 过滤的分组
  const showConversations = (activeTab === 'all' || activeTab === 'conversations') && localConversations.length > 0
  const showMessages = (activeTab === 'all' || activeTab === 'messages') && msgs.length > 0
  const showDocuments = (activeTab === 'all' || activeTab === 'documents') && docs.length > 0
  const showKbs = (activeTab === 'all' || activeTab === 'knowledgeBases') && localKbs.length > 0

  // 计算某个分组的起始索引（用于高亮）
  let runningIndex = 0
  const convStart = runningIndex
  if (showConversations) runningIndex += localConversations.length
  const msgStart = runningIndex
  if (showMessages) runningIndex += msgs.length
  const docStart = runningIndex
  if (showDocuments) runningIndex += docs.length
  const kbStart = runningIndex

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
      {focused && query.trim() && (
        <div className="absolute left-0 right-0 top-full mt-1 bg-white rounded-lg border border-slate-200 shadow-lg max-h-[400px] overflow-y-auto z-50">
          {loading ? (
            <div className="px-3 py-6 text-center text-xs text-slate-400">
              <svg className="w-5 h-5 mx-auto mb-1.5 text-emerald-400 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 0 1 8-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              搜索中...
            </div>
          ) : !hasResults ? (
            <div className="px-3 py-6 text-center text-xs text-slate-400">未找到相关结果</div>
          ) : (
            <>
              {/* Tab 栏 */}
              <div className="flex items-center gap-1 px-2 py-1.5 border-b border-slate-100 sticky top-0 bg-white z-10">
                <TabBtn active={activeTab === 'all'} onClick={() => setActiveTab('all')} count={totalCount}>全部</TabBtn>
                {localConversations.length > 0 && <TabBtn active={activeTab === 'conversations'} onClick={() => setActiveTab('conversations')} count={localConversations.length}>会话</TabBtn>}
                {msgs.length > 0 && <TabBtn active={activeTab === 'messages'} onClick={() => setActiveTab('messages')} count={msgs.length}>消息</TabBtn>}
                {docs.length > 0 && <TabBtn active={activeTab === 'documents'} onClick={() => setActiveTab('documents')} count={docs.length}>文档</TabBtn>}
                {localKbs.length > 0 && <TabBtn active={activeTab === 'knowledgeBases'} onClick={() => setActiveTab('knowledgeBases')} count={localKbs.length}>知识库</TabBtn>}
              </div>

              <div className="py-1">
                {showConversations && (
                  <Section title="会话">
                    {localConversations.map((c, i) => (
                      <Item key={c.id} highlighted={highlightIndex === convStart + i} onClick={() => { onSelectConversation(c.id); setFocused(false) }}>
                        <span className="text-slate-400 text-xs">💬</span>
                        <span className="text-xs text-slate-700 truncate">{c.title}</span>
                      </Item>
                    ))}
                  </Section>
                )}
                {showMessages && (
                  <Section title="聊天消息">
                    {msgs.map((m, i) => (
                      <Item key={m.id} highlighted={highlightIndex === msgStart + i} onClick={() => { onSelectMessage({ conversationId: m.conversation_id, conversationTitle: m.conversation_title }); setFocused(false) }}>
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="text-slate-400 text-xs">{m.role === 'user' ? '👤' : '🤖'}</span>
                            <span className="text-xs text-slate-400 truncate">{m.conversation_title}</span>
                          </div>
                          {m.highlight ? (
                            <span className="text-xs text-slate-600 truncate pl-5" dangerouslySetInnerHTML={{ __html: m.highlight }} />
                          ) : (
                            <span className="text-xs text-slate-600 truncate pl-5">{m.content}</span>
                          )}
                        </div>
                      </Item>
                    ))}
                  </Section>
                )}
                {showDocuments && (
                  <Section title="文档">
                    {docs.map((d, i) => (
                      <Item key={d.doc_id + i} highlighted={highlightIndex === docStart + i} onClick={() => { onSelectDocument({ docId: d.doc_id, kbId: d.kb_id, source: d.source }); setFocused(false) }}>
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <span className="text-xs text-slate-700 truncate">📄 {d.source || d.doc_id}</span>
                          {d.highlight && <span className="text-[11px] text-slate-400 truncate pl-5" dangerouslySetInnerHTML={{ __html: d.highlight }} />}
                        </div>
                      </Item>
                    ))}
                  </Section>
                )}
                {showKbs && (
                  <Section title="知识库">
                    {localKbs.map((kb, i) => (
                      <Item key={kb.id} highlighted={highlightIndex === kbStart + i} onClick={() => { onSelectKnowledgeBase(kb.id); setFocused(false) }}>
                        <span className="text-slate-400 text-xs">📚</span>
                        <span className="text-xs text-slate-700 truncate">{kb.name}</span>
                      </Item>
                    ))}
                  </Section>
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

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-1">
      <div className="px-3 py-1 text-[10px] font-semibold text-slate-400 uppercase tracking-wide">{title}</div>
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
