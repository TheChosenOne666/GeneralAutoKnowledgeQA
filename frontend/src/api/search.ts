/** 全局搜索 API — 文档 chunk（ES BM25 + 向量融合）+ 聊天消息（ES BM25 多字段）。
 *
 * 搜索运算符支持：
 * - `"精确短语"` → 精确匹配
 * - `-排除词` → 排除含该词的结果
 * - `+必含词` → 结果必须包含该词
 * - 普通词 → BM25 OR 匹配
 */

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
export interface SearchResponse {
  documents: DocSearchResult[]
  messages: MsgSearchResult[]
  total_documents: number
  total_messages: number
}

export const searchApi = {
  /** 全局搜索（文档 chunk BM25 + 向量融合 + 聊天消息 BM25 多字段）。*/
  global: (
    query: string,
    options?: {
      kbIds?: string[]
      topK?: number
      from?: number
      enableSemantic?: boolean
    },
  ) => {
    const params: Record<string, unknown> = { query }
    if (options?.kbIds) params.kbIds = options.kbIds
    if (options?.topK != null) params.topK = options.topK
    if (options?.from != null) params.from = options.from
    if (options?.enableSemantic != null) params.enableSemantic = options.enableSemantic
    return api
      .get<BaseResponse<SearchResponse>>('/search/global', { params })
      .then((r) => r.data.data)
  },

  /** 搜索运算符说明。*/
  operators: () =>
    api
      .get<BaseResponse<{ operators: Array<{ syntax: string; description: string; example: string }> }>>(
        '/search/operators',
      )
      .then((r) => r.data.data),
}
