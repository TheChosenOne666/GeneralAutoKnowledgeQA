/** 知识库 API — 对接 Java 后端。*/

import { api } from './client'
import type { BaseResponse, Document, KnowledgeBase } from '@/types'

export const knowledgeApi = {
  /** 知识库列表（按 scope 筛选）。*/
  list: (scope?: string) =>
    api.get<BaseResponse<KnowledgeBase[]>>('/knowledge/list', { params: { scope } }).then((r) => r.data.data),

  /** 创建知识库。*/
  add: (data: { name: string; description?: string; scope: string }) =>
    api.post<BaseResponse<string>>('/knowledge/add', data).then((r) => r.data.data),

  /** 文档列表。*/
  listDocuments: (kbId: string) =>
    api.get<BaseResponse<Document[]>>('/knowledge/document/list', { params: { kbId } }).then((r) => r.data.data),

  /** 上传文档。*/
  uploadDocument: (kbId: string, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api
      .post<BaseResponse<string>>(`/knowledge/document/upload?kbId=${kbId}`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data.data)
  },

  /** 删除文档。*/
  deleteDocument: (id: string) =>
    api.post<BaseResponse<boolean>>('/knowledge/document/delete', { id }).then((r) => r.data.data),
}
