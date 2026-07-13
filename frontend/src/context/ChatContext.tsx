/** 会话 Context — 管理会话列表、当前选中会话、刷新。供 AppLayout 与 ChatPage 共享。 */

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { chatApi } from '@/api/chat'
import { useAuth } from '@/hooks/useAuth'
import { useTenant } from '@/context/TenantContext'
import type { Conversation } from '@/types'

const ACTIVE_CONVERSATION_KEY = 'xiongda_active_conversation'

interface ChatContextValue {
  conversations: Conversation[]
  activeId: string | null
  setActiveId: (id: string | null) => void
  refresh: () => Promise<void>
}

const ChatContext = createContext<ChatContextValue | null>(null)

export function ChatProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const { currentTenantId } = useTenant()
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveIdState] = useState<string | null>(null)

  // 持久化当前会话 id：刷新/重进平台时自动恢复"当前对话窗口"
  const setActiveId = (id: string | null) => {
    if (id) window.localStorage.setItem(ACTIVE_CONVERSATION_KEY, id)
    else window.localStorage.removeItem(ACTIVE_CONVERSATION_KEY)
    setActiveIdState(id)
  }

  const refresh = useCallback(async () => {
    try {
      const list = await chatApi.listConversations()
      setConversations(list)
      // 若持久化的 activeId 对应会话已被删，则清除，避免停留在不存在的会话
      setActiveIdState((prev) => {
        if (prev && !list.some((c) => c.id === prev)) {
          window.localStorage.removeItem(ACTIVE_CONVERSATION_KEY)
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
    window.localStorage.removeItem(ACTIVE_CONVERSATION_KEY)
    setConversations([])
    if (user) void refresh()
  }, [user?.id, currentTenantId, refresh])

  return (
    <ChatContext.Provider value={{ conversations, activeId, setActiveId, refresh }}>
      {children}
    </ChatContext.Provider>
  )
}

export function useChat() {
  const ctx = useContext(ChatContext)
  if (!ctx) throw new Error('useChat 必须在 ChatProvider 内使用')
  return ctx
}
