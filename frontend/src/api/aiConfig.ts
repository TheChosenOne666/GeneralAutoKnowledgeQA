/** AI 模型配置接口 — 对齐后端 AiConfigController。*/

import { api } from './client'
import type { AIConfig, AIConfigUpdateRequest, BaseResponse } from '@/types'

export const aiConfigApi = {
  /** 获取当前用户的 AI 配置（不含 API Key 明文）。*/
  getConfig: async (): Promise<AIConfig | null> => {
    const res = await api.get<BaseResponse<AIConfig>>('/ai-config/')
    return res.data.data
  },

  /** 更新当前用户的 AI 配置，返回更新后的配置（不含 API Key 明文）。*/
  updateConfig: async (body: AIConfigUpdateRequest): Promise<AIConfig> => {
    const res = await api.post<BaseResponse<AIConfig>>('/ai-config/update', body)
    return res.data.data
  },

  /** 获取平台级默认 AI 配置（仅平台超管）。*/
  getPlatformDefault: async (): Promise<AIConfig | null> => {
    const res = await api.get<BaseResponse<AIConfig>>('/ai-config/platform-default')
    return res.data.data
  },

  /** 更新平台级默认 AI 配置（仅平台超管）。*/
  updatePlatformDefault: async (body: AIConfigUpdateRequest): Promise<AIConfig> => {
    const res = await api.post<BaseResponse<AIConfig>>('/ai-config/platform-default', body)
    return res.data.data
  },
}
