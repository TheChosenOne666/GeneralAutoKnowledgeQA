/** 知识库 API — 对接 Java 后端。*/

import { api } from './client'
import type { AxiosProgressEvent } from 'axios'
import type { BaseResponse, Document, KnowledgeBase } from '@/types'

/** 文档原文件预览前置校验结果（删除 / 被修改检测）。*/
export interface FileStatus {
  /** 原文件是否存在。*/
  exists: boolean
  /** 原文件是否相对上传时被修改（大小不一致）。*/
  changed: boolean
  /** 友好提示文案（缺失 / 被修改时返回）。*/
  message?: string
  filename?: string
  fileType?: string
}

/** 文档单页内容（真实分页，与引用来源页码一致，M4-4 增强）。*/
export interface DocumentPage {
  /** 页码（从 1 开始）。*/
  pageNo: number
  /** 该页提取文本。*/
  text: string
}

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

  /** 上传文档。
   * @param onProgress 可选，上传进度回调（百分比 0-100），依赖 axios onUploadProgress。*/
  uploadDocument: (kbId: string, file: File, onProgress?: (pct: number) => void) => {
    const formData = new FormData()
    formData.append('file', file)
    return api
      .post<BaseResponse<string>>(`/knowledge/document/upload?kbId=${kbId}`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (e: AxiosProgressEvent) => {
          if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
        },
      })
      .then((r) => r.data.data)
  },

  /** 删除文档。*/
  deleteDocument: (id: string) =>
    api.post<BaseResponse<boolean>>('/knowledge/document/delete', { id }).then((r) => r.data.data),

  /** 批量删除文档。
   * @param ids 文档 ID 列表；后端 fail-fast 全量鉴权，任一无权限即全部不删除。
   * @returns 实际删除成功的数量。*/
  batchDeleteDocuments: (ids: string[]) =>
    api.post<BaseResponse<number>>('/knowledge/document/batch-delete', { ids }).then((r) => r.data.data),

  /** 取消文档处理（软取消，保留文档记录，清理已写向量并停止增强）。*/
  cancelDocument: (id: string) =>
    api.post<BaseResponse<boolean>>('/knowledge/document/cancel', { id }).then((r) => r.data.data),

  /** 获取文档提取全文（供「查看内容」弹窗）。*/
  getDocumentContent: (docId: string) =>
    api.get<BaseResponse<string>>('/knowledge/document/content', { params: { docId } }).then((r) => r.data.data),

  /** 获取文档按真实分页的文本（供预览精准翻页，覆盖 pdf/docx/txt/md，M4-4 增强）。
   * PDF 为后端 PyMuPDF 真实页码，docx/txt/md 为按 CHARS_PER_PAGE 估算页码，均与引用来源一致。*/
  getDocumentPages: (docId: string) =>
    api.get<BaseResponse<DocumentPage[]>>('/knowledge/document/pages', { params: { docId } }).then((r) => r.data.data),

  /** 获取文档原始文件流 URL（供非鉴权场景占位，M4-4；实际预览请用 fetchDocumentFile 携带 token）。*/
  getDocumentFileUrl: (docId: string) => `/api/knowledge/document/file/${docId}`,

  /** 携带 token 拉取文档原始文件流（Blob），供 iframe 预览（M4-4）。
   * 直接把裸 URL 放进 iframe.src 会因浏览器原生请求不带 Authorization 而触发 401，
   * 故必须经 axios 携带 JWT 后再以 Blob URL 注入 iframe。*/
  fetchDocumentFile: (docId: string) =>
    api.get<Blob>('/knowledge/document/file/' + docId, { responseType: 'blob' }).then((r) => r.data),

  /** 预览前置校验：检测原文件是否已被删除 / 修改，避免直接加载文件流触发错误页。*/
  checkDocumentFileStatus: (docId: string) =>
    api.get<BaseResponse<FileStatus>>(`/knowledge/document/file/status/${docId}`).then((r) => r.data.data),
}
