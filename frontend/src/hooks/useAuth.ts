/** 认证 Hook — 管理登录状态。*/

import { useCallback, useEffect, useState } from 'react'
import { authApi } from '@/api/auth'
import { clearToken, getToken, setToken } from '@/api/client'
import type { User } from '@/types'

export function useAuth() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)

  const fetchUser = useCallback(async () => {
    const token = getToken()
    if (!token) {
      setLoading(false)
      return
    }
    try {
      const me = await authApi.me()
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
    setToken(res.access_token)
    setUser(res.user)
    return res.user
  }

  const register = async (name: string, email: string, password: string) => {
    const res = await authApi.register(name, email, password)
    setToken(res.access_token)
    setUser(res.user)
    return res.user
  }

  const logout = () => {
    clearToken()
    setUser(null)
  }

  return { user, loading, login, register, logout, refresh: fetchUser }
}
