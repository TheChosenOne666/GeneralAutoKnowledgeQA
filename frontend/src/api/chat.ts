/** 聊天 API — 会话管理 + SSE 流式问答。*/

import { api, getToken } from './client'
import type { BaseResponse, Conversation, Message } from '@/types'

export const chatApi = {
  /** 会话列表。*/
  listConversations: () =>
    api.get<BaseResponse<Conversation[]>>('/chat/conversation/list').then((r) => r.data.data),

  /** 创建会话。*/
  createConversation: (title?: string) =>
    api.post<BaseResponse<string>>('/chat/conversation/create', null, { params: { title } }).then((r) => r.data.data),

  /** 会话消息列表。*/
  listMessages: (conversationId: string) =>
    api.get<BaseResponse<Message[]>>('/chat/message/list', { params: { conversationId } }).then((r) => r.data.data),

  /** SSE 流式问答 — 返回 ReadableStream reader。*/
  streamChat: async (content: string, conversationId?: string) => {
    const token = getToken()
    const response = await fetch('/api/chat/message/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ content, conversationId: conversationId || null, mode: 'rag' }),
    })
    if (!response.body) throw new Error('SSE 流式响应不支持')
    return response.body.getReader()
  },
}
