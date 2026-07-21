/** 全局搜索 API — 文档 chunk + 聊天消息（后端 ES BM25），知识库名称/会话标题由前端本地过滤。*/

import { api } from './client'
import type { BaseResponse } from '@/types'

/** 文档搜索结果项。*/
export interface DocSearchResult {
  doc_id: string
  kb_id: string
  source: string
  content: string
  highlight: string
  score: number
}

/** 消息搜索结果项。*/
export interface MsgSearchResult {
  id: string
  conversation_id: string
  conversation_title: string
  role: string
  content: string
  highlight: string
  score: number
}

/** 全局搜索响应。*/
export interface SearchResultGroup {
  documents: DocSearchResult[]
  messages: MsgSearchResult[]
  /** 知识库名称和会话标题由前端本地过滤后填入。*/
  conversations: import('@/types').Conversation[]
  knowledgeBases: import('@/types').KnowledgeBase[]
}

export const searchApi = {
  /** 全局搜索（文档 chunk + 聊天消息）。*/
  global: (query: string, kbIds?: string[], topK?: number) =>
    api
      .get<BaseResponse<{ documents: DocSearchResult[]; messages: MsgSearchResult[] }>>(
        '/search/global',
        { params: { query, kbIds, topK } },
      )
      .then((r) => r.data.data),
}
