/** 成员管理 API — 列表 / 改角色 / 停用 / 移除 / 邀请 / 接受邀请。*/

import { api } from './client'
import type { BaseResponse, InviteInfoVO, InviteResultVO, LoginUserVO, Member, Role } from '@/types'

export const userApi = {
  /** 获取本租户成员列表（租户管理员）。*/
  listMembers: () => api.get<BaseResponse<Member[]>>('/user/list').then((r) => r.data.data),

  /** 修改成员角色 / 启用停用。*/
  updateMember: (id: string, payload: { role?: Role; isActive?: number }) =>
    api.post<BaseResponse<boolean>>('/user/update', { id, ...payload }).then((r) => r.data.data),

  /** 软删除成员。*/
  removeMember: (id: string) =>
    api.post<BaseResponse<boolean>>('/user/remove', { id }).then((r) => r.data.data),

  /** 生成邀请链接（租户管理员）。*/
  inviteMember: (name: string, email: string, role: Role) =>
    api.post<BaseResponse<InviteResultVO>>('/user/invite', { name, email, role }).then((r) => r.data.data),

  /** 获取邀请链接详情（公开）。*/
  getInviteInfo: (token: string) =>
    api.get<BaseResponse<InviteInfoVO>>('/user/invite/info', { params: { token } }).then((r) => r.data.data),

  /** 通过邀请链接注册并自动登录（公开）。*/
  acceptInvite: (token: string, name: string, email: string, userPassword: string) =>
    api
      .post<BaseResponse<LoginUserVO>>('/user/invite/accept', { token, name, email, userPassword })
      .then((r) => r.data.data),
}
