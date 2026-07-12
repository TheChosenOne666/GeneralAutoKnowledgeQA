/** 问答页 — 左侧会话历史（AppLayout）+ 右侧问答区（按用户截图）。 */

import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import 'highlight.js/styles/github.css'
import { useNavigate } from 'react-router-dom'
import { chatApi } from '@/api/chat'
import { aiConfigApi } from '@/api/aiConfig'
import { useChat } from '@/context/ChatContext'
import type { ChatHistoryItem, Message, SourceItem, AIConfig } from '@/types'

/** 模型类型展示名。*/
const MODEL_LABELS: Record<'llm' | 'embedding', string> = {
  llm: 'LLM 大语言模型',
  embedding: 'Embedding 向量化模型',
}

/** 判定某一模型是否已配置：需同时具备 provider 与 model（API Key 不在前端可见范围内）。*/
function isModelConfigured(cfg: AIConfig | null, key: 'llm' | 'embedding'): boolean {
  if (!cfg) return false
  const provider = key === 'llm' ? cfg.llmProvider : cfg.embeddingProvider
  const model = key === 'llm' ? cfg.llmModel : cfg.embeddingModel
  return Boolean(provider && model)
}

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  sources?: SourceItem[]
}

/** 解析后端以 JSON 字符串存储的来源（容错）。*/
function parseSources(raw: string | null): SourceItem[] {
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? (arr as SourceItem[]) : []
  } catch {
    return []
  }
}

/** 引用来源卡片 — 文件名 + 页码 + 查看原文（展开检索片段，原文件查看见 M4-4）。*/
function SourceCard({ source }: { source: SourceItem }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="rounded-lg bg-white/70 border border-emerald-100 px-3 py-2">
      <button onClick={() => setOpen((v) => !v)} className="w-full flex items-center gap-2 text-left">
        <svg className="w-4 h-4 text-brand-500 flex-shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
        </svg>
        <span className="text-xs font-medium text-slate-600 truncate flex-1">{source.source}</span>
        <span className="text-[10px] text-slate-400 flex-shrink-0">第 {source.page} 页</span>
        <span className="text-[10px] text-brand-500 flex-shrink-0">{open ? '收起' : '查看原文'}</span>
      </button>
      {open && <p className="mt-2 text-xs text-slate-500 leading-relaxed whitespace-pre-wrap">{source.content}</p>}
    </div>
  )
}

const SUGGESTIONS = [
  '文档"这是一个测试文档.docx"的具体内容是什么？',
  '如何编辑首页文档中的愿景和目标部分？',
  'Weknora是什么平台或工具？',
  '如何利用img标签的onerror属性执行JavaScript代码？',
  '"Hello.txt"文档的内容是什么？',
  '测试数据.xlsx中SKU0079的库存量是多少？',
]

export default function ChatPage() {
  const { activeId, setActiveId, refresh } = useChat()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [conversationId, setConversationId] = useState<string | null>(null)
  const loadToken = useRef(0)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const navigate = useNavigate()
  const [missingModels, setMissingModels] = useState<('llm' | 'embedding')[]>([])
  // 模型配置「填了但填错」（API Key/模型名等运行时错误），由 Python 的 error 事件触发
  const [modelConfigError, setModelConfigError] = useState(false)

  // 进入问答页即拉取 AI 配置，判定 LLM / Embedding 是否缺失并常驻提示
  useEffect(() => {
    aiConfigApi
      .getConfig()
      .then((cfg) => {
        const missing: ('llm' | 'embedding')[] = []
        if (!isModelConfigured(cfg, 'llm')) missing.push('llm')
        if (!isModelConfigured(cfg, 'embedding')) missing.push('embedding')
        setMissingModels(missing)
      })
      .catch(() => {
        // 配置接口异常时不打扰对话，静默忽略
      })
  }, [])

  /** 输入框随内容自动增高，超过 200px 后改为滚动。*/
  const autoResize = () => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`
  }

  // 选中会话变化时加载历史消息
  useEffect(() => {
    // 切换会话：取消进行中的流式并恢复输入态，避免输入框卡在禁用
    abortRef.current?.abort()
    setStreaming(false)
    if (!activeId) {
      setMessages([])
      setConversationId(null)
      return
    }
    const token = ++loadToken.current
    setMessages([])
    chatApi
      .listMessages(activeId)
      .then((msgs: Message[]) => {
        if (token !== loadToken.current) return
        setMessages(
          msgs.map((m) => ({
            role: m.role as 'user' | 'assistant',
            content: m.content,
            sources: parseSources(m.sources),
          })),
        )
        setConversationId(activeId)
      })
      .catch(() => {
        if (token !== loadToken.current) return
        setMessages([{ role: 'assistant', content: '加载历史消息失败，请稍后重试。' }])
      })
  }, [activeId])

  const sendMessage = async (text: string) => {
    if (!text.trim() || streaming) return
    setInput('')
    if (textareaRef.current) textareaRef.current.style.height = 'auto'

    // 多轮历史：发送前已有的已完成消息（不含当前问题）
    const history: ChatHistoryItem[] = messages
      .filter((m) => m.content)
      .map((m) => ({ role: m.role, content: m.content }))

    const controller = new AbortController()
    abortRef.current = controller
    setStreaming(true)
    setModelConfigError(false)
    setMessages((prev) => [...prev, { role: 'user', content: text }, { role: 'assistant', content: '' }])

    try {
      const reader = await chatApi.streamChat(text, conversationId || undefined, history, controller.signal)
      const decoder = new TextDecoder()
      let buffer = ''
      let aiContent = ''
      let convId = conversationId
      let currentEvent = ''

      const updateLast = (patch: Partial<ChatMessage>) =>
        setMessages((prev) => {
          const next = [...prev]
          const idx = next.length - 1
          if (next[idx]?.role === 'assistant') next[idx] = { ...next[idx], ...patch }
          return next
        })

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const raw of lines) {
          const line = raw.trim()
          if (!line) continue
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
            continue
          }
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (!data || data === '[DONE]') continue
          try {
            const parsed = JSON.parse(data)
            if (currentEvent === 'sources' && Array.isArray(parsed.sources)) {
              updateLast({ sources: parsed.sources as SourceItem[] })
            } else if (currentEvent === 'token' && parsed.content) {
              aiContent += parsed.content
              updateLast({ content: aiContent })
            } else if (currentEvent === 'error') {
              // Python 抛出的模型配置错误（API Key/模型名等填错），引导用户重配
              const msg = parsed.message || '模型调用失败'
              if (parsed.error_type === 'MODEL_CONFIG_ERROR') {
                setModelConfigError(true)
              }
              updateLast({ content: `错误：${msg}` })
            } else if (currentEvent === 'done') {
              if (parsed.conversation_id && !convId) {
                convId = parsed.conversation_id
                setConversationId(convId)
              }
              if (convId) {
                if (!activeId) setActiveId(convId)
                void refresh()
              }
            }
          } catch {
            // 非 JSON 行跳过
          }
          currentEvent = ''
        }
      }
    } catch (err) {
      if ((err as { name?: string })?.name === 'AbortError') return
      setMessages((prev) => {
        const next = [...prev]
        next[next.length - 1] = {
          role: 'assistant',
          content: `错误：${err instanceof Error ? err.message : '请求失败'}`,
        }
        return next
      })
    } finally {
      setStreaming(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage(input)
    }
  }

  return (
    <div className="h-full flex flex-col relative overflow-hidden bg-white">
      {/* 模型配置错误提示（填了但填错：API Key/模型名等运行时错误） */}
      {modelConfigError && (
        <div className="flex-shrink-0 px-6 pt-3">
          <div className="max-w-3xl mx-auto flex items-start gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3">
            <svg className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
            </svg>
            <div className="flex-1 text-sm text-red-800 leading-relaxed">
              <span className="font-semibold">模型配置不正确</span>，AI 调用失败，请检查 API Key、模型名或向量维度后重新配置。
            </div>
            <button
              onClick={() => navigate('/ai-config')}
              className="flex-shrink-0 px-3 py-1.5 rounded-lg bg-red-500 text-white text-xs font-semibold hover:bg-red-600 transition"
            >
              去配置
            </button>
          </div>
        </div>
      )}

      {/* 未配置模型常驻提示（不随消息滚动） */}
      {missingModels.length > 0 && (
        <div className="flex-shrink-0 px-6 pt-3">
          <div className="max-w-3xl mx-auto flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
            <svg className="w-5 h-5 text-amber-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z" />
            </svg>
            <div className="flex-1 text-sm text-amber-800 leading-relaxed">
              尚未配置{' '}
              {missingModels.map((m, i) => (
                <span key={m}>
                  {i > 0 && ' 与 '}
                  <span className="font-semibold">{MODEL_LABELS[m]}</span>
                </span>
              ))}
              ，AI 问答将无法调用对应能力，请先完成配置。
            </div>
            <button
              onClick={() => navigate('/ai-config')}
              className="flex-shrink-0 px-3 py-1.5 rounded-lg bg-amber-500 text-white text-xs font-semibold hover:bg-amber-600 transition"
            >
              去配置
            </button>
          </div>
        </div>
      )}

      {/* 主内容区 */}
      <div className="flex-1 overflow-auto px-6">
        {messages.length === 0 ? (
          /* 欢迎区 */
          <div className="max-w-3xl mx-auto pt-24 text-center">
            <h1 className="text-2xl font-bold text-slate-800 mb-3">基于知识库内容问答 – AI 问答</h1>
            <p className="text-sm text-slate-400 mb-8">你可以这样问我</p>
            <div className="flex flex-wrap justify-center gap-3">
              {SUGGESTIONS.map((q) => (
                <button
                  key={q}
                  onClick={() => sendMessage(q)}
                  className="px-4 py-2.5 rounded-full border border-emerald-200 bg-white text-slate-600 text-sm font-medium cursor-pointer transition-all hover:border-brand-400 hover:text-emerald-600 hover:bg-emerald-50"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
        ) : (
          /* 消息列表 */
          <div className="max-w-3xl mx-auto pt-6 space-y-6">
            {messages.map((msg, i) => (
              <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                {msg.role === 'assistant' && (
                  <div className="w-8 h-8 rounded-full bg-gradient-to-br from-brand-500 to-teal-400 flex items-center justify-center text-white text-sm font-bold mr-3 flex-shrink-0">熊</div>
                )}
                <div className={`max-w-[80%] ${msg.role === 'user' ? 'bg-gradient-to-r from-brand-600 to-brand-500 text-white rounded-2xl rounded-tr-sm' : 'bg-emerald-50 text-slate-700 rounded-2xl rounded-tl-sm'} px-4 py-3`}>
                  {msg.role === 'user' ? (
                    <span className="text-sm whitespace-pre-wrap">{msg.content}</span>
                  ) : (
                    <div className="text-sm leading-relaxed break-words">
                      {msg.content ? (
                        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>
                          {msg.content}
                        </ReactMarkdown>
                      ) : (
                        streaming && i === messages.length - 1 && '思考中...'
                      )}
                    </div>
                  )}
                  {msg.role === 'assistant' && msg.sources && msg.sources.length > 0 && (
                    <div className="mt-3 pt-3 border-t border-emerald-200 space-y-2">
                      <p className="text-xs font-medium text-slate-400">引用来源（{msg.sources.length}）</p>
                      {msg.sources.map((s, si) => (
                        <SourceCard key={si} source={s} />
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 底部输入区（固定底部） */}
      <div className="flex-shrink-0 px-6 py-5 bg-white border-t border-emerald-100">
        <div className="max-w-3xl mx-auto">
          {/* 知识库标签 */}
          <div className="flex gap-2 mb-2">
            <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-50 text-brand-700 text-xs font-medium border border-emerald-200">
              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25" />
              </svg>
              测试
              <button className="ml-1 hover:text-brand-900">
                <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                </svg>
              </button>
            </span>
          </div>

          {/* 输入框 */}
          <div className="bg-white border border-emerald-200 rounded-2xl shadow-lg shadow-emerald-100/50 overflow-hidden focus-within:border-brand-400 focus-within:ring-2 focus-within:ring-brand-500/20 transition-all">
            <textarea
              ref={textareaRef}
              value={input}
              onChange={(e) => {
                setInput(e.target.value)
                autoResize()
              }}
              onKeyDown={handleKeyDown}
              placeholder="输入问题，将基于知识库和网络搜索回答"
              className="w-full px-5 py-4 bg-transparent text-sm text-slate-700 placeholder-slate-400 outline-none resize-none max-h-[200px] overflow-y-auto"
              rows={1}
              disabled={streaming}
            />
            <div className="px-3 pb-3 flex items-center justify-between">
              <div className="flex items-center gap-2">
                {/* 智能推理 */}
                <button className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 text-xs font-medium hover:bg-emerald-50 hover:text-brand-700 cursor-pointer transition">
                  <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z" />
                  </svg>
                  智能推理
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                  </svg>
                </button>
                {/* 图片 */}
                <button className="w-8 h-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-brand-600 hover:bg-emerald-50 cursor-pointer transition" title="图片">
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909m-18 3.75h16.5a2.25 2.25 0 0 0 2.25-2.25V6a2.25 2.25 0 0 0-2.25-2.25H3.75A2.25 2.25 0 0 0 1.5 6v12a2.25 2.25 0 0 0 2.25 2.25Z" />
                  </svg>
                </button>
                {/* 附件 */}
                <button className="w-8 h-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-brand-600 hover:bg-emerald-50 cursor-pointer transition" title="附件">
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="m18.375 12.739-7.693 7.693a4.5 4.5 0 0 1-6.364-6.364l10.94-10.94A3 3 0 1 1 19.5 7.372L8.552 18.32m.009-.01-.01.01m5.699-9.941-7.667 7.667" />
                  </svg>
                </button>
              </div>
              <div className="flex items-center gap-2">
                {/* 模型选择 */}
                <button className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 text-xs font-medium hover:bg-emerald-50 hover:text-brand-700 cursor-pointer transition">
                  <span className="w-2 h-2 rounded-full bg-emerald-500" />
                  deepseek-v3.2
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                  </svg>
                </button>
                {streaming ? (
                  <button
                    onClick={() => abortRef.current?.abort()}
                    className="w-8 h-8 rounded-lg flex items-center justify-center bg-slate-400 text-white hover:bg-slate-500 cursor-pointer shadow-sm transition"
                    title="停止生成"
                  >
                    <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
                      <rect x="6" y="6" width="12" height="12" rx="2" />
                    </svg>
                  </button>
                ) : (
                  <button
                    onClick={() => sendMessage(input)}
                    disabled={!input.trim()}
                    className="w-8 h-8 rounded-lg flex items-center justify-center bg-emerald-500 text-white hover:bg-brand-600 cursor-pointer shadow-sm transition disabled:opacity-50"
                    title="发送"
                  >
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5" />
                    </svg>
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
