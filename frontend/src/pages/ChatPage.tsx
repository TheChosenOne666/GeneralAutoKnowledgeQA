/** 问答页 — 左侧会话历史（AppLayout）+ 右侧问答区（按用户截图）。 */

import { useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import 'highlight.js/styles/github.css'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
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

/** 单条 Agent 推理步骤（M4-1 智能推理模式）。*/
interface AgentStep {
  step: number
  type: 'thought' | 'action' | 'observation'
  content: string
  tool?: string
  input?: string
  success?: boolean
}

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  sources?: SourceItem[]
  /** M4-1：智能推理（Agent）模式的推理步骤树。*/
  agentSteps?: AgentStep[]
  /** 该消息产生时使用的问答模式。*/
  mode?: 'rag' | 'web' | 'agent'
  /** 消息时间（YYYY-MM-DD HH:mm），用于展示在用户消息下方。*/
  time?: string
}

/** 将时间格式化为「YYYY-MM-DD HH:mm」。*/
function formatTime(input: string | number | Date): string {
  const d =
    typeof input === 'number'
      ? new Date(input)
      : typeof input === 'string'
        ? new Date(input)
        : input
  if (isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
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

/** 引用来源卡片 — 文件名 + 页码（或 URL）+ 查看原文（展开检索片段，M4-3 支持联网搜索结果）。*/
function SourceCard({ source }: { source: SourceItem }) {
  const [open, setOpen] = useState(false)
  const isWeb = source.kb_id === 'web'
  return (
    <div className="rounded-lg bg-white/70 border border-emerald-100 px-3 py-2">
      <button onClick={() => setOpen((v) => !v)} className="w-full flex items-center gap-2 text-left">
        {isWeb ? (
          <svg className="w-4 h-4 text-blue-500 flex-shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 0 0 8.716-6.747M12 21a9.004 9.004 0 0 1-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 0 1 7.843 4.582M12 3a8.997 8.997 0 0 0-7.843 4.582m15.686 0A11.953 11.953 0 0 1 12 10.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0A8.959 8.959 0 0 1 21 12c0 .778-.099 1.533-.284 2.253m0 0A17.919 17.919 0 0 1 12 16.5c-3.162 0-6.133-.815-8.716-2.247m0 0A9.015 9.015 0 0 1 3 12c0-1.605.42-3.113 1.157-4.418" />
          </svg>
        ) : (
          <svg className="w-4 h-4 text-brand-500 flex-shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
          </svg>
        )}
        <span className="text-xs font-medium text-slate-600 truncate flex-1">{source.source}</span>
        {isWeb ? (
          <span className="text-[10px] text-blue-500 flex-shrink-0 truncate max-w-[120px]" title={source.doc_id}>
            {source.doc_id?.replace(/^https?:\/\//, '')}
          </span>
        ) : (
          <span className="text-[10px] text-slate-400 flex-shrink-0">第 {source.page} 页</span>
        )}
        <span className="text-[10px] text-brand-500 flex-shrink-0">{open ? '收起' : '查看原文'}</span>
      </button>
      {open && <p className="mt-2 text-xs text-slate-500 leading-relaxed whitespace-pre-wrap">{source.content}</p>}
    </div>
  )
}

/** 单步推理步骤的图标与配色（M4-1 智能推理）。*/
const STEP_META: Record<AgentStep['type'], { label: string; icon: JSX.Element; badge: string }> = {
  thought: {
    label: '思考',
    badge: 'bg-violet-100 text-violet-700',
    icon: (
      <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z" />
    ),
  },
  action: {
    label: '调用工具',
    badge: 'bg-amber-100 text-amber-700',
    icon: (
      <path strokeLinecap="round" strokeLinejoin="round" d="M11.42 15.17 17.25 21A2.652 2.652 0 0 0 21 17.25l-5.877-5.877M11.42 15.17l2.496-3.03c.317-.384.74-.626 1.208-.766M11.42 15.17l-4.655 5.653a2.548 2.548 0 1 1-3.586-3.586l6.837-5.63m5.108-.233c.55-.164 1.163-.188 1.743-.14a4.5 4.5 0 0 0 4.486-6.336l-3.276 3.277a3.004 3.004 0 0 1-2.25-2.25l3.276-3.276a4.5 4.5 0 0 0-6.336 4.486c.091 1.076-.071 2.264-.904 2.95l-.102.085m-1.745 1.437L5.909 7.5H4.5L2.25 3.75l1.5-1.5L7.5 4.5v1.409l4.26 4.26m-1.745 1.437 1.745-1.437m6.615 8.206L15.75 15.75M4.867 19.125h.008v.008h-.008v-.008Z" />
    ),
  },
  observation: {
    label: '观察结果',
    badge: 'bg-emerald-100 text-emerald-700',
    icon: (
      <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z" />
    ),
  },
}

/** 推理过程面板 — 展示 Agent 多步推理的 Think/Act/Observe 步骤树。*/
function AgentSteps({ steps }: { steps: AgentStep[] }) {
  const [open, setOpen] = useState(true)
  return (
    <div className="mt-3 pt-3 border-t border-emerald-200">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1.5 text-xs font-medium text-slate-400 hover:text-brand-600 transition"
      >
        <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z" />
        </svg>
        推理过程（{steps.length} 步）
        <svg className={`w-3 h-3 transition-transform ${open ? '' : '-rotate-90'}`} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
        </svg>
      </button>
      {open && (
        <div className="mt-2 space-y-2">
          {steps.map((s, i) => {
            const meta = STEP_META[s.type]
            const fail = s.type === 'observation' && s.success === false
            return (
              <div key={i} className="rounded-lg bg-white/70 border border-emerald-100 px-3 py-2">
                <div className="flex items-center gap-2 mb-1">
                  <svg className={`w-3.5 h-3.5 flex-shrink-0 ${fail ? 'text-red-500' : 'text-brand-500'}`} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    {meta.icon}
                  </svg>
                  <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${fail ? 'bg-red-100 text-red-600' : meta.badge}`}>{meta.label}</span>
                  {s.tool && <span className="text-[10px] text-slate-400 truncate">@{s.tool}</span>}
                </div>
                {s.type === 'action' && s.input ? (
                  <pre className="text-[11px] text-slate-500 bg-slate-50 rounded px-2 py-1 overflow-x-auto whitespace-pre-wrap break-words">{s.input}</pre>
                ) : (
                  <p className="text-xs text-slate-500 leading-relaxed whitespace-pre-wrap">{s.content}</p>
                )}
              </div>
            )
          })}
        </div>
      )}
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
  const { user } = useAuth()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  // 问答模式（知识库 / 联网搜索 / 智能推理 Agent），默认知识库
  const [mode, setMode] = useState<'rag' | 'web' | 'agent'>('rag')
  const [conversationId, setConversationId] = useState<string | null>(null)
  const loadToken = useRef(0)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const navigate = useNavigate()
  // 自动滚动：发送后跳到底部、流式回复时跟随底部动态加载
  const scrollRef = useRef<HTMLDivElement>(null)
  const atBottomRef = useRef(true)
  const followRef = useRef(false)

  const handleScroll = () => {
    const el = scrollRef.current
    if (!el) return
    atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }

  const scrollToBottom = (behavior: ScrollBehavior = 'auto') => {
    const el = scrollRef.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior })
  }
  const [missingModels, setMissingModels] = useState<('llm' | 'embedding')[]>([])
  // 模型配置「填了但填错」（API Key/模型名等运行时错误），由 Python 的 error 事件触发
  const [modelConfigError, setModelConfigError] = useState(false)

  // 对话模型下拉：从 AI 配置读取可选模型并支持切换
  const [aiConfig, setAiConfig] = useState<AIConfig | null>(null)
  const [selectedModel, setSelectedModel] = useState('')
  const [modelMenuOpen, setModelMenuOpen] = useState(false)

  /** 配置中可用的 LLM 模型（去重：默认模型 + 多模型列表）。*/
  const availableModels = useMemo(() => {
    if (!aiConfig) return []
    const set = new Set<string>()
    if (aiConfig.llmModel) set.add(aiConfig.llmModel)
    aiConfig.llmModels?.forEach((m) => set.add(m))
    return Array.from(set)
  }, [aiConfig])

  // 可选模型变化且当前选中项不在列表中时，自动选中第一项
  useEffect(() => {
    if (availableModels.length > 0 && !availableModels.includes(selectedModel)) {
      setSelectedModel(availableModels[0])
    }
  }, [availableModels, selectedModel])

  // 进入问答页即拉取 AI 配置，判定 LLM / Embedding 是否缺失并常驻提示
  useEffect(() => {
    aiConfigApi
      .getConfig()
      .then((cfg) => {
        setAiConfig(cfg)
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
            time: m.createTime ? formatTime(m.createTime) : undefined,
          })),
        )
        setConversationId(activeId)
        atBottomRef.current = true
        scrollToBottom('auto')
      })
      .catch(() => {
        if (token !== loadToken.current) return
        setMessages([{ role: 'assistant', content: '加载历史消息失败，请稍后重试。' }])
      })
  }, [activeId])

  // AI 回复逐字追加（流式）时，若用户原本在底部或正处于主动跟随，则自动滚动到底部
  useEffect(() => {
    if (atBottomRef.current || followRef.current) {
      scrollToBottom('auto')
    }
  }, [messages])

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
    followRef.current = true // 发送后主动跳到底部并跟随 AI 回复
    setMessages((prev) => [...prev, { role: 'user', content: text, time: formatTime(new Date()) }, { role: 'assistant', content: '', mode }])
    scrollToBottom('smooth')

    try {
      const reader = await chatApi.streamChat(
        text,
        conversationId || undefined,
        history,
        controller.signal,
        selectedModel || undefined,
        mode,
      )
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
            } else if (currentEvent === 'agent_step' && parsed) {
              // M4-1：智能推理步骤（thought / action / observation）累积展示
              setMessages((prev) => {
                const next = [...prev]
                const idx = next.length - 1
                const m = next[idx]
                if (m?.role === 'assistant') {
                  const steps = [...(m.agentSteps || [])]
                  steps.push({
                    step: parsed.step,
                    type: parsed.type,
                    content: parsed.content || '',
                    tool: parsed.tool,
                    input: parsed.input,
                    success: parsed.success,
                  })
                  next[idx] = { ...m, agentSteps: steps }
                }
                return next
              })
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
      followRef.current = false
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
      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-auto px-6">
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
            {messages.map((msg, i) => {
              const isUser = msg.role === 'user'
              const displayName = isUser ? user?.name || '我' : '熊答AI'
              return (
                <div key={i} className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
                  {!isUser && (
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-brand-500 to-teal-400 flex items-center justify-center text-white text-sm font-bold mr-3 flex-shrink-0">熊</div>
                  )}
                  <div className={`flex flex-col ${isUser ? 'items-end' : 'items-start'} max-w-[80%]`}>
                    <span className="text-xs text-slate-400 mb-1 px-1">{displayName}</span>
                    <div className={`${isUser ? 'bg-gradient-to-r from-brand-600 to-brand-500 text-white rounded-2xl rounded-tr-sm' : 'bg-emerald-50 text-slate-700 rounded-2xl rounded-tl-sm'} px-4 py-3`}>
                      {isUser ? (
                        <span className="text-sm whitespace-pre-wrap">{msg.content}</span>
                      ) : (
                        <div className="text-sm leading-relaxed break-words">
                          {msg.content ? (
                            <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>
                              {msg.content}
                            </ReactMarkdown>
                          ) : (
                            streaming && i === messages.length - 1 && !msg.agentSteps?.length && '思考中...'
                          )}
                        </div>
                      )}
                      {msg.role === 'assistant' && msg.agentSteps && msg.agentSteps.length > 0 && (
                        <AgentSteps steps={msg.agentSteps} />
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
                    {isUser && msg.time && (
                      <span className="text-[11px] text-slate-400 mt-1 px-1">{msg.time}</span>
                    )}
                  </div>
                  {isUser && (
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-slate-400 to-slate-500 flex items-center justify-center text-white text-sm font-bold ml-3 flex-shrink-0">
                      {displayName.slice(0, 1)}
                    </div>
                  )}
                </div>
              )
            })}
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
              placeholder={
                mode === 'agent'
                  ? '输入问题，AI 将多步推理并检索知识库和网络回答'
                  : mode === 'web'
                    ? '输入问题，将从互联网搜索最新信息回答'
                    : '输入问题，将基于知识库回答'
              }
              className="w-full px-5 py-4 bg-transparent text-sm text-slate-700 placeholder-slate-400 outline-none resize-none max-h-[200px] overflow-y-auto"
              rows={1}
              disabled={streaming}
            />
            <div className="px-3 pb-3 flex items-center justify-between">
              <div className="flex items-center gap-2">
                {/* 问答模式切换（知识库 / 联网搜索 / 智能推理 Agent，M4-3） */}
                <div className="flex items-center gap-0.5 bg-emerald-50 border border-emerald-200 rounded-lg p-0.5">
                  <button
                    onClick={() => setMode('rag')}
                    className={`px-2.5 py-1 rounded-md text-xs font-medium transition ${mode === 'rag' ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500 hover:text-brand-600'}`}
                  >
                    知识库
                  </button>
                  <button
                    onClick={() => setMode('web')}
                    className={`px-2.5 py-1 rounded-md text-xs font-medium transition ${mode === 'web' ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500 hover:text-brand-600'}`}
                  >
                    联网搜索
                  </button>
                  <button
                    onClick={() => setMode('agent')}
                    className={`flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium transition ${mode === 'agent' ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500 hover:text-brand-600'}`}
                  >
                    <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z" />
                    </svg>
                    智能推理
                  </button>
                </div>
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
                {/* 模型选择（动态载入已配置模型，可切换） */}
                {availableModels.length > 0 && (
                  <div className="relative">
                    <button
                      type="button"
                      onClick={() => setModelMenuOpen((v) => !v)}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 text-xs font-medium hover:bg-emerald-50 hover:text-brand-700 cursor-pointer transition"
                    >
                      <span className="w-2 h-2 rounded-full bg-emerald-500" />
                      {selectedModel || '选择模型'}
                      <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                      </svg>
                    </button>
                    {modelMenuOpen && (
                      <div className="absolute bottom-full mb-2 right-0 w-44 rounded-xl border border-emerald-200 bg-white shadow-lg py-1 z-20">
                        {availableModels.map((m) => (
                          <button
                            key={m}
                            type="button"
                            onClick={() => {
                              setSelectedModel(m)
                              setModelMenuOpen(false)
                            }}
                            className={`w-full text-left px-3 py-2 text-xs hover:bg-emerald-50 ${m === selectedModel ? 'text-brand-700 font-semibold' : 'text-slate-600'}`}
                          >
                            {m}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
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
