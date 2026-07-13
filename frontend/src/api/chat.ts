/** 聊天 API — 会话管理 + SSE 流式问答。*/

import { api, getToken } from './client'
import type { BaseResponse, Conversation, Message, ChatHistoryItem } from '@/types'

export const chatApi = {
  /** 会话列表。*/
  listConversations: () =>
    api
      .get<BaseResponse<Conversation[]>>('/chat/conversation/list')
      .then((r) => r.data.data.map((c) => ({ ...c, id: c.id }))),

  /** 创建会话。*/
  createConversation: (title?: string) =>
    api
      .post<BaseResponse<number>>('/chat/conversation/create', null, { params: { title } })
      .then((r) => String(r.data.data)),

  /** 重命名会话。*/
  renameConversation: (id: string, title: string) =>
    api
      .post<BaseResponse<boolean>>('/chat/conversation/rename', { id, title })
      .then((r) => r.data.data),

  /** 删除会话（含其消息）。*/
  deleteConversation: (id: string) =>
    api
      .post<BaseResponse<boolean>>('/chat/conversation/delete', null, { params: { id } })
      .then((r) => r.data.data),

  /** 会话消息列表。*/
  listMessages: (conversationId: string) =>
    api
      .get<BaseResponse<Message[]>>('/chat/message/list', { params: { conversationId } })
      .then((r) => r.data.data),

  /** SSE 流式问答 — 返回 ReadableStream reader。可传 signal 主动中断，model 指定对话模型，mode 指定问答模式。*/
  streamChat: async (
    content: string,
    conversationId?: string,
    history?: ChatHistoryItem[],
    signal?: AbortSignal,
    model?: string,
    mode?: 'rag' | 'agent',
  ) => {
    const token = getToken()
    const response = await fetch('/api/chat/message/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({
        content,
        conversationId: conversationId ? conversationId : null,
        mode: mode ?? 'rag',
        history: history ?? [],
        model: model ? model : null,
      }),
      ...(signal ? { signal } : {}),
    })
    if (!response.body) throw new Error('SSE 流式响应不支持')
    return response.body.getReader()
  },
}
