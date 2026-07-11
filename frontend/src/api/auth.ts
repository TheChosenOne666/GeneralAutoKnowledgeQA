/** 认证 API — 对接 Java 后端模板风格接口。*/

import { api } from './client'
import type { BaseResponse, TokenResponse, User } from '@/types'

export const authApi = {
  login: (email: string, userPassword: string) =>
    api.post<BaseResponse<TokenResponse>>('/user/login', { email, userPassword }).then((r) => r.data.data),

  register: (name: string, email: string, userPassword: string) =>
    api.post<BaseResponse<string>>('/user/register', { name, email, userPassword }).then((r) => r.data.data),

  getLoginUser: () =>
    api.get<BaseResponse<TokenResponse>>('/user/get/login').then((r) => r.data.data),
}
