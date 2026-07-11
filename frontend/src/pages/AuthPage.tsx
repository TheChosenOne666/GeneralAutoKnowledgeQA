/** 登录/注册页。*/

import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'

export default function AuthPage() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      if (mode === 'login') {
        await login(email, password)
      } else {
        await register(name, email, password)
      }
      navigate('/chat')
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '操作失败'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-emerald-50/40 px-4">
      <div className="w-full max-w-md bg-white rounded-3xl p-8 shadow-xl border border-emerald-100">
        <div className="flex items-center gap-3 mb-8 justify-center">
          <img src="/logo.png" alt="熊答" className="w-16 h-16 rounded-2xl object-cover" />
          <span className="text-3xl font-extrabold text-slate-800">熊答</span>
        </div>

        {/* Tab 切换 */}
        <div className="flex gap-8 border-b border-emerald-100 mb-6">
          <button
            onClick={() => setMode('login')}
            className={`pb-3 text-base font-semibold transition ${
              mode === 'login' ? 'text-emerald-600 border-b-2 border-emerald-500' : 'text-slate-400'
            }`}
          >
            登录
          </button>
          <button
            onClick={() => setMode('register')}
            className={`pb-3 text-base font-semibold transition ${
              mode === 'register' ? 'text-emerald-600 border-b-2 border-emerald-500' : 'text-slate-400'
            }`}
          >
            注册
          </button>
        </div>

        {error && (
          <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          {mode === 'register' && (
            <div>
              <label className="block text-sm font-medium text-slate-600 mb-2">姓名</label>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full px-4 py-3 rounded-xl border border-emerald-200 bg-white text-slate-800 text-sm"
                placeholder="你的姓名"
                required
              />
            </div>
          )}
          <div>
            <label className="block text-sm font-medium text-slate-600 mb-2">邮箱</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full px-4 py-3 rounded-xl border border-emerald-200 bg-white text-slate-800 text-sm"
              placeholder="you@company.com"
              required
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-600 mb-2">密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-4 py-3 rounded-xl border border-emerald-200 bg-white text-slate-800 text-sm"
              placeholder="至少6位"
              required
            />
          </div>
          <button
            type="submit"
            disabled={loading}
            className="w-full py-3 rounded-xl bg-gradient-to-r from-brand-600 to-brand-500 text-white font-semibold text-sm hover:shadow-lg transition disabled:opacity-50"
          >
            {loading ? '处理中...' : mode === 'login' ? '登录' : '注册'}
          </button>
        </form>
      </div>
    </div>
  )
}
