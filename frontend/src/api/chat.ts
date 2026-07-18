/** 聊天 API — 会话管理 + SSE 流式问答。*/

import { api, getToken } from './client'
import type { AxiosProgressEvent } from 'axios'
import type { BaseResponse, Conversation, Message, ChatHistoryItem } from '@/types'

/** 问答附件 VO（M5-9，对应后端 ChatAttachmentVO）。*/
export interface ChatAttachmentVO {
  /** 附件 ID（雪花 ID，后端序列化为字符串防前端精度丢失）。*/
  id: string
  filename: string
  fileType: string
  fileSize: number
  /** 分类：image / attachment。*/
  category: string
  /** 文件访问 URL（前端展示用，携带 JWT 鉴权）。*/
  url: string
}

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

  /**
   * 上传问答附件（M5-9，图片或通用文档，临时上下文不入知识库）。
   * @param category image / attachment
   * @param file 文件
   * @param onProgress 上传进度回调（百分比 0-100）
   */
  uploadAttachment: (
    category: 'image' | 'attachment',
    file: File,
    onProgress?: (pct: number) => void,
  ) => {
    const formData = new FormData()
    // 用 ASCII 安全文件名（UUID + 扩展名）避免 Tomcat multipart 中文文件名变 ?
    const ext = file.name.includes('.') ? file.name.slice(file.name.lastIndexOf('.')) : ''
    const safeName = `${crypto.randomUUID()}${ext}`
    formData.append('file', file, safeName)
    // 中文文件名用 encodeURIComponent 编码，避免 Tomcat multipart text 字段 ISO-8859-1 解码乱码
    formData.append('originalFilename', encodeURIComponent(file.name))
    return api
      .post<BaseResponse<ChatAttachmentVO>>('/chat/attachment/upload', formData, {
        params: { category },
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (e: AxiosProgressEvent) => {
          if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
        },
      })
      .then((r) => r.data.data)
  },

  /** SSE 流式问答 — 返回 ReadableStream reader。可传 signal 主动中断，model 指定对话模型，mode 指定问答模式。
   *
   * M5-9：新增 kbIds（指定检索的知识库范围）、imageIds（多模态图片）、attachmentIds（一次性文档附件）。
   * 注意：所有 ID 均为 string 类型（雪花 ID 超 JS 安全整数范围，必须用字符串避免精度丢失，Java 端 Jackson 自动转 Long）。
   */
  streamChat: async (
    content: string,
    conversationId?: string,
    history?: ChatHistoryItem[],
    signal?: AbortSignal,
    model?: string,
    mode?: 'rag' | 'web' | 'agent',
    kbIds?: string[],
    imageIds?: string[],
    attachmentIds?: string[],
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
        kbIds: kbIds ?? [],
        imageIds: imageIds ?? [],
        attachmentIds: attachmentIds ?? [],
      }),
      ...(signal ? { signal } : {}),
    })
    if (!response.body) throw new Error('SSE 流式响应不支持')
    return response.body.getReader()
  },
}
