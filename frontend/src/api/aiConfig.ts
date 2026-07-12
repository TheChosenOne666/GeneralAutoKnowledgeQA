/** AI 模型配置接口 — 对齐后端 AiConfigController。*/

import { api } from './client'
import type { AIConfig, BaseResponse } from '@/types'

export const aiConfigApi = {
  /** 获取当前用户的 AI 配置（不含 API Key 明文）。*/
  getConfig: async (): Promise<AIConfig | null> => {
    const res = await api.get<BaseResponse<AIConfig>>('/ai-config/')
    return res.data.data
  },
}
