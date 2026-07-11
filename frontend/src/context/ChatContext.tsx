/** 会话 Context — 管理会话列表、当前选中会话、刷新。供 AppLayout 与 ChatPage 共享。 */

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { chatApi } from '@/api/chat'
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
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveIdState] = useState<string | null>(() =>
    typeof window !== 'undefined' ? window.localStorage.getItem(ACTIVE_CONVERSATION_KEY) ?? null : null,
  )

  // 持久化当前会话 id：刷新/重进平台时自动恢复"当前对话窗口"
  const setActiveId = (id: string | null) => {
    if (id) window.localStorage.setItem(ACTIVE_CONVERSATION_KEY, id)
    else window.localStorage.removeItem(ACTIVE_CONVERSATION_KEY)
    setActiveIdState(id)
  }

  const refresh = async () => {
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
  }

  useEffect(() => {
    void refresh()
  }, [])

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
