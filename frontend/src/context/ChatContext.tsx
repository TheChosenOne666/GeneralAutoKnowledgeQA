/** 会话 Context — 管理会话列表、当前选中会话、消息缓存与流式状态。
 * 消息与流式状态提升到本层（按会话 id 缓存），使问答页在路由切换卸载/重挂时
 * 不丢失正在进行的对话与流式回复；刷新浏览器后从 localStorage 恢复 activeId 并加载历史。 */

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { chatApi } from '@/api/chat'
import { useAuth } from '@/hooks/useAuth'
import { useTenant } from '@/context/TenantContext'
import type { ChatMessage, Conversation } from '@/types'

const ACTIVE_CONVERSATION_KEY = 'xiongda_active_conversation'
// 新会话在流式 done 事件前 activeId 为空，进行中的消息暂存此占位 key，done 注册后迁移到真实 id
const PENDING_KEY = '__pending__'

interface ChatContextValue {
  conversations: Conversation[]
  activeId: string | null
  setActiveId: (id: string | null) => void
  refresh: () => Promise<void>
  /** 当前会话的消息（跨路由切换保留于内存）。*/
  messages: ChatMessage[]
  /** 函数式更新当前会话消息。*/
  setMessages: (updater: ChatMessage[] | ((prev: ChatMessage[]) => ChatMessage[])) => void
  /** 标记某会话已成功从后端加载过历史，路由重挂时跳过重新加载以免覆盖进行中的流式回复。*/
  markLoaded: (convId: string) => void
  /** 某会话是否已从后端加载过历史。*/
  isLoaded: (convId: string | null) => boolean
  /** 全局流式标记（一次仅一个会话在流式回复）。*/
  isStreaming: boolean
  setStreaming: (v: boolean) => void
}

const ChatContext = createContext<ChatContextValue | null>(null)

export function ChatProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const { currentTenantId } = useTenant()
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeIdState, setActiveIdState] = useState<string | null>(() => {
    try {
      return window.localStorage.getItem(ACTIVE_CONVERSATION_KEY)
    } catch {
      return null
    }
  })
  const activeIdRef = useRef<string | null>(activeIdState)
  // 按会话 id 缓存消息，跨路由卸载保留；activeId 为空时用 PENDING_KEY 暂存进行中新会话
  const [messagesByConv, setMessagesByConv] = useState<Record<string, ChatMessage[]>>({})
  // 已成功从后端加载过历史的会话 id，避免路由重挂重复加载覆盖流式回复
  const [loadedConvIds, setLoadedConvIds] = useState<Set<string>>(() => new Set())
  const [streaming, setStreaming] = useState(false)

  const activeKey = activeIdState ?? PENDING_KEY

  const messages = useMemo(() => messagesByConv[activeKey] ?? [], [messagesByConv, activeKey])

  const setMessages = useCallback(
    (updater: ChatMessage[] | ((prev: ChatMessage[]) => ChatMessage[])) => {
      setMessagesByConv((prev) => {
        const cur = prev[activeKey] ?? []
        const nextVal = typeof updater === 'function' ? (updater as (p: ChatMessage[]) => ChatMessage[])(cur) : updater
        if (nextVal === cur) return prev
        return { ...prev, [activeKey]: nextVal }
      })
    },
    [activeKey],
  )

  const markLoaded = useCallback((convId: string) => {
    setLoadedConvIds((prev) => new Set(prev).add(convId))
  }, [])

  const isLoaded = useCallback((convId: string | null) => (convId ? loadedConvIds.has(convId) : false), [loadedConvIds])

  // 持久化当前会话 id：刷新/重进平台时自动恢复"当前对话窗口"
  const setActiveId = useCallback((id: string | null) => {
    const prev = activeIdRef.current
    if (id) {
      if (prev === null) {
        // 新会话流式 done 注册：把进行中的 PENDING 消息迁移到真实 id，并标记已加载避免重载覆盖
        setMessagesByConv((m) =>
          m[PENDING_KEY]?.length ? { ...m, [id]: m[PENDING_KEY], [PENDING_KEY]: [] } : m,
        )
        setLoadedConvIds((s) => new Set(s).add(id))
      }
      window.localStorage.setItem(ACTIVE_CONVERSATION_KEY, id)
    } else {
      window.localStorage.removeItem(ACTIVE_CONVERSATION_KEY)
      // 新建对话：清空进行中的 PENDING 消息（区别于路由切走，后者不会触发本分支）
      setMessagesByConv((m) => (m[PENDING_KEY] ? { ...m, [PENDING_KEY]: [] } : m))
    }
    activeIdRef.current = id
    setActiveIdState(id)
  }, [])

  const refresh = useCallback(async () => {
    try {
      const list = await chatApi.listConversations()
      setConversations(list)
      // 若持久化的 activeId 对应会话已被删，则清除，避免停留在不存在的会话
      setActiveIdState((prev) => {
        if (prev && !list.some((c) => c.id === prev)) {
          window.localStorage.removeItem(ACTIVE_CONVERSATION_KEY)
          activeIdRef.current = null
          return null
        }
        return prev
      })
    } catch {
      // 加载失败静默，避免阻塞页面
    }
  }, [])

  // 登录用户变化（如 admin → 超管）或登出时，或超管切换操作租户时，清空残留会话状态，
  // 避免跨账号/跨租户串号看到他人对话记录（后端 listMessages 也已加归属校验兜底）
  useEffect(() => {
    setActiveIdState(null)
    activeIdRef.current = null
    window.localStorage.removeItem(ACTIVE_CONVERSATION_KEY)
    setConversations([])
    setMessagesByConv({})
    setLoadedConvIds(new Set())
    setStreaming(false)
    if (user) void refresh()
  }, [user?.id, currentTenantId, refresh])

  return (
    <ChatContext.Provider
      value={{ conversations, activeId: activeIdState, setActiveId, refresh, messages, setMessages, markLoaded, isLoaded, isStreaming: streaming, setStreaming }}
    >
      {children}
    </ChatContext.Provider>
  )
}

export function useChat() {
  const ctx = useContext(ChatContext)
  if (!ctx) throw new Error('useChat 必须在 ChatProvider 内使用')
  return ctx
}
