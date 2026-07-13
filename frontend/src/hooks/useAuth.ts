/** 认证 Hook — 管理登录状态。*/

import { useCallback, useEffect, useState } from 'react'
import { authApi } from '@/api/auth'
import { userApi } from '@/api/user'
import { clearToken, getToken, setToken } from '@/api/client'
import type { LoginUserVO } from '@/types'

export function useAuth() {
  const [user, setUser] = useState<LoginUserVO | null>(null)
  const [loading, setLoading] = useState(true)

  const fetchUser = useCallback(async () => {
    const token = getToken()
    if (!token) {
      setLoading(false)
      return
    }
    try {
      const me = await authApi.getLoginUser()
      setUser(me)
    } catch {
      clearToken()
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchUser()
  }, [fetchUser])

  const login = async (email: string, password: string) => {
    const res = await authApi.login(email, password)
    setToken(res.token)
    setUser(res)
    return res
  }

  const register = async (name: string, email: string, password: string) => {
    // 仅注册账户，不自动登录；由用户手动前往登录页登录
    return authApi.register(name, email, password)
  }

  const logout = () => {
    clearToken()
    setUser(null)
  }

  /** 通过邀请链接注册并自动登录。*/
  const acceptInvite = async (token: string, name: string, email: string, password: string) => {
    const res = await userApi.acceptInvite(token, name, email, password)
    setToken(res.token)
    setUser(res)
    return res
  }

  return { user, loading, login, register, logout, acceptInvite, refresh: fetchUser }
}
