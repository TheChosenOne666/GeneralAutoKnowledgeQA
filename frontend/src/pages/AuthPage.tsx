/** 登录/注册页 — 按设计稿实现：粒子动效 + 极光光晕 + 毛玻璃 + 左右分栏。
 *  支持通过 ?token=xxx 进入「接受邀请」模式（加入现有租户，而非新建租户）。 */

import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { userApi } from '@/api/user'
import type { InviteInfoVO } from '@/types'

const STRENGTH_COLORS = ['bg-red-500', 'bg-orange-500', 'bg-yellow-500', 'bg-emerald-500']
const STRENGTH_LABELS = ['弱', '一般', '较强', '强']
const ROLE_LABELS: Record<string, string> = {
  super_admin: '平台超管',
  tenant_admin: '租户管理员',
  member: '普通成员',
}

export default function AuthPage() {
  const { login, register, acceptInvite } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const inviteToken = searchParams.get('token')

  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [showConfirmPassword, setShowConfirmPassword] = useState(false)
  const [pwdStrength, setPwdStrength] = useState(0)

  // 邀请注册：加载邀请详情
  const [inviteInfo, setInviteInfo] = useState<InviteInfoVO | null>(null)
  const [inviteError, setInviteError] = useState('')

  const canvasRef = useRef<HTMLCanvasElement>(null)

  /** 粒子系统：90 粒子 + 距离连线 + 鼠标交互吸引。 */
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    let W = (canvas.width = window.innerWidth)
    let H = (canvas.height = window.innerHeight)
    const mouse = { x: null as number | null, y: null as number | null }
    const COUNT = 90
    const COLORS = ['#10B981', '#34D399', '#2DD4BF', '#6EE7B7']

    const particles = Array.from({ length: COUNT }, () => ({
      x: Math.random() * W,
      y: Math.random() * H,
      vx: (Math.random() - 0.5) * 0.5,
      vy: (Math.random() - 0.5) * 0.5,
      r: Math.random() * 2 + 0.5,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
    }))

    let raf = 0
    const draw = () => {
      ctx.clearRect(0, 0, W, H)
      for (const p of particles) {
        p.x += p.vx
        p.y += p.vy
        if (p.x < 0 || p.x > W) p.vx *= -1
        if (p.y < 0 || p.y > H) p.vy *= -1
        if (mouse.x !== null && mouse.y !== null) {
          const dx = p.x - mouse.x
          const dy = p.y - mouse.y
          const dist = Math.sqrt(dx * dx + dy * dy)
          if (dist < 140) {
            p.x += (dx / dist) * 0.8
            p.y += (dy / dist) * 0.8
          }
        }
        ctx.beginPath()
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
        ctx.fillStyle = p.color
        ctx.globalAlpha = 0.6
        ctx.fill()
      }
      ctx.globalAlpha = 1
      for (let i = 0; i < particles.length; i++) {
        for (let j = i + 1; j < particles.length; j++) {
          const dx = particles[i].x - particles[j].x
          const dy = particles[i].y - particles[j].y
          const dist = Math.sqrt(dx * dx + dy * dy)
          if (dist < 130) {
            ctx.beginPath()
            ctx.moveTo(particles[i].x, particles[i].y)
            ctx.lineTo(particles[j].x, particles[j].y)
            ctx.strokeStyle = `rgba(16,185,129,${0.18 * (1 - dist / 130)})`
            ctx.lineWidth = 0.6
            ctx.stroke()
          }
        }
        if (mouse.x !== null && mouse.y !== null) {
          const dx = particles[i].x - mouse.x
          const dy = particles[i].y - mouse.y
          const dist = Math.sqrt(dx * dx + dy * dy)
          if (dist < 180) {
            ctx.beginPath()
            ctx.moveTo(particles[i].x, particles[i].y)
            ctx.lineTo(mouse.x, mouse.y)
            ctx.strokeStyle = `rgba(45,212,191,${0.35 * (1 - dist / 180)})`
            ctx.lineWidth = 0.8
            ctx.stroke()
          }
        }
      }
      raf = requestAnimationFrame(draw)
    }
    draw()

    const handleResize = () => {
      W = canvas.width = window.innerWidth
      H = canvas.height = window.innerHeight
    }
    const handleMouseMove = (e: MouseEvent) => {
      mouse.x = e.clientX
      mouse.y = e.clientY
    }
    const handleMouseOut = () => {
      mouse.x = null
      mouse.y = null
    }
    window.addEventListener('resize', handleResize)
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseout', handleMouseOut)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', handleResize)
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseout', handleMouseOut)
    }
  }, [])

  useEffect(() => {
    if (!inviteToken) return
    setMode('register')
    userApi
      .getInviteInfo(inviteToken)
      .then(setInviteInfo)
      .catch((err) => setInviteError(err instanceof Error ? err.message : '邀请链接无效或已失效'))
  }, [inviteToken])

  const switchMode = (newMode: 'login' | 'register') => {
    setMode(newMode)
    setConfirmPassword('')
    setError('')
    setShowPassword(false)
    setShowConfirmPassword(false)
  }

  /** 密码强度检测：长度>=8 / 含字母 / 含数字 / 含特殊字符，各 1 分。 */
  const checkPasswordStrength = (v: string) => {
    let score = 0
    if (v.length >= 8) score++
    if (/[A-Z]/.test(v) || /[a-z]/.test(v)) score++
    if (/\d/.test(v)) score++
    if (/[^A-Za-z0-9]/.test(v)) score++
    setPwdStrength(score)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (mode === 'register' && password !== confirmPassword) {
      setError('两次输入的密码不一致')
      return
    }
    setLoading(true)
    try {
      if (mode === 'login') {
        await login(email, password)
      } else if (inviteToken) {
        await acceptInvite(inviteToken, name, email, password)
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
    <>
      <canvas ref={canvasRef} id="particles" />
      <div className="aurora" />

      <div className="relative z-10 h-screen flex items-center justify-center px-4 overflow-hidden">
        {/* 左侧品牌展示区（大屏显示） */}
        <div className="hidden lg:flex flex-col justify-center max-w-md mr-16 text-slate-700">
          <div className="flex items-center gap-3 mb-6">
            <img src="/logo.png" alt="熊答" className="w-16 h-16 rounded-2xl object-cover logo-glow" />
            <span className="text-2xl font-extrabold tracking-tight text-slate-800">熊答</span>
          </div>
          <h1 className="text-4xl font-black leading-tight mb-4 shimmer-text">
            让企业知识
            <br />
            真正流动起来
          </h1>
          <p className="text-slate-500 text-lg leading-relaxed mb-8">
            基于 RAG + Agent 的多租户智能问答平台，新员工秒懂公司规范，告别重复沟通。
          </p>
          <div className="space-y-3">
            {[
              '混合检索 + 重排序，回答精准可溯源',
              '多租户 RBAC，数据隔离安全合规',
              'ReAct Agent 多步推理，复杂问题也搞定',
            ].map((text, i) => (
              <div key={i} className="flex items-center gap-3 text-slate-600">
                <span className="w-8 h-8 rounded-lg feature-badge flex items-center justify-center">
                  <svg className="w-4 h-4 text-brand-600" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d={ICONS.features[i]} />
                  </svg>
                </span>
                {text}
              </div>
            ))}
          </div>
        </div>

        {/* 右侧认证卡片（毛玻璃） */}
        <div className="glass-card w-full max-w-md rounded-3xl p-8 fade-in">
          {/* Logo（小屏显示） */}
          <div className="flex lg:hidden items-center gap-3 mb-6 justify-center">
            <img src="/logo.png" alt="熊答" className="w-12 h-12 rounded-xl object-cover logo-glow" />
            <span className="text-xl font-extrabold text-slate-800">熊答</span>
          </div>

          {/* 邀请横幅 */}
          {inviteToken && (
            <div className="mb-5 rounded-xl bg-emerald-50 border border-emerald-100 px-4 py-3 text-sm text-slate-600">
              {inviteError ? (
                <span className="text-red-600">{inviteError}</span>
              ) : inviteInfo ? (
                <span>
                  <span className="font-semibold text-emerald-700">{inviteInfo.inviterName}</span> 邀请你加入{' '}
                  <span className="font-semibold text-emerald-700">{inviteInfo.tenantName}</span>
                  （{ROLE_LABELS[inviteInfo.role] ?? inviteInfo.role}）
                </span>
              ) : (
                <span>正在加载邀请信息…</span>
              )}
            </div>
          )}

          {/* Tab 切换（邀请模式下隐藏） */}
          {!inviteToken && (
            <div className="flex gap-8 border-b border-emerald-100 mb-7 relative">
              <button
                onClick={() => switchMode('login')}
                className={`relative pb-3 text-base font-semibold transition ${
                  mode === 'login' ? 'tab-active' : 'text-slate-400 hover:text-slate-600'
                }`}
              >
                登录
              </button>
              <button
                onClick={() => switchMode('register')}
                className={`relative pb-3 text-base font-semibold transition ${
                  mode === 'register' ? 'tab-active' : 'text-slate-400 hover:text-slate-600'
                }`}
              >
                注册
              </button>
            </div>
          )}

          {error && (
            <div className="mb-4 px-4 py-2 rounded-lg bg-red-50 text-red-600 text-sm fade-in">{error}</div>
          )}

          <form onSubmit={handleSubmit} className="space-y-5 fade-in" key={mode}>
            {/* 注册才显示姓名 */}
            {mode === 'register' && (
              <div>
                <label className="block text-sm font-medium text-slate-600 mb-2">姓名</label>
                <div className="input-field rounded-xl flex items-center gap-3 px-4 py-3">
                  <svg className="w-5 h-5 text-slate-400 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d={ICONS.user} />
                  </svg>
                  <input
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="你的姓名"
                    className="bg-transparent flex-1 text-slate-800 placeholder-slate-400 outline-none text-sm"
                    required
                  />
                </div>
              </div>
            )}

            {/* 邮箱 */}
            <div>
              <label className="block text-sm font-medium text-slate-600 mb-2">邮箱</label>
              <div className="input-field rounded-xl flex items-center gap-3 px-4 py-3">
                <svg className="w-5 h-5 text-slate-400 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d={ICONS.email} />
                </svg>
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@company.com"
                  className="bg-transparent flex-1 text-slate-800 placeholder-slate-400 outline-none text-sm"
                  required
                />
              </div>
            </div>

            {/* 密码 */}
            <div>
              <label className="block text-sm font-medium text-slate-600 mb-2">密码</label>
              <div className="input-field rounded-xl flex items-center gap-3 px-4 py-3">
                <svg className="w-5 h-5 text-slate-400 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d={ICONS.lock} />
                </svg>
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => {
                    setPassword(e.target.value)
                    if (mode === 'register') checkPasswordStrength(e.target.value)
                  }}
                  placeholder={mode === 'register' ? '至少 8 位，含字母和数字' : '输入密码'}
                  minLength={6}
                  className="bg-transparent flex-1 text-slate-800 placeholder-slate-400 outline-none text-sm"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className={`shrink-0 transition ${showPassword ? 'text-brand-600' : 'text-slate-400 hover:text-slate-600'}`}
                >
                  <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d={ICONS.eye} />
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
                  </svg>
                </button>
              </div>
              {/* 密码强度条（仅注册） */}
              {mode === 'register' && (
                <>
                  <div className="flex gap-1.5 mt-2">
                    {[0, 1, 2, 3].map((i) => (
                      <div
                        key={i}
                        className={`strength-bar h-1 flex-1 rounded-full ${
                          i < pwdStrength ? STRENGTH_COLORS[pwdStrength - 1] : 'bg-slate-200'
                        }`}
                      />
                    ))}
                  </div>
                  <p className="text-xs text-slate-400 mt-1.5">
                    密码强度：{password ? STRENGTH_LABELS[pwdStrength - 1] || '弱' : '—'}
                  </p>
                </>
              )}
            </div>

            {/* 确认密码（仅注册） */}
            {mode === 'register' && (
              <div>
                <label className="block text-sm font-medium text-slate-600 mb-2">确认密码</label>
                <div className="input-field rounded-xl flex items-center gap-3 px-4 py-3">
                  <svg className="w-5 h-5 text-slate-400 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d={ICONS.shield} />
                  </svg>
                  <input
                    type={showConfirmPassword ? 'text' : 'password'}
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    placeholder="再次输入密码"
                    minLength={6}
                    className="bg-transparent flex-1 text-slate-800 placeholder-slate-400 outline-none text-sm"
                    required
                  />
                  <button
                    type="button"
                    onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                    className={`shrink-0 transition ${showConfirmPassword ? 'text-brand-600' : 'text-slate-400 hover:text-slate-600'}`}
                  >
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d={ICONS.eye} />
                      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
                    </svg>
                  </button>
                </div>
              </div>
            )}

            {/* 登录辅助：记住我 + 忘记密码 */}
            {mode === 'login' && (
              <div className="flex items-center justify-between text-sm">
                <label className="flex items-center gap-2 text-slate-500 cursor-pointer">
                  <input type="checkbox" className="rounded border-emerald-200 bg-white/50 text-brand-500" />
                  记住我
                </label>
                <a href="#" className="text-brand-600 hover:text-brand-700 font-medium">
                  忘记密码？
                </a>
              </div>
            )}

            {/* 注册协议勾选 */}
            {mode === 'register' && (
              <label className="flex items-start gap-2 text-xs text-slate-500 cursor-pointer">
                <input type="checkbox" className="mt-0.5 rounded border-emerald-200 bg-white/50 text-brand-500" required />
                <span>
                  我已阅读并同意 <a href="#" className="text-brand-600 hover:text-brand-700">用户协议</a> 和{' '}
                  <a href="#" className="text-brand-600 hover:text-brand-700">隐私政策</a>
                </span>
              </label>
            )}

            {/* 提交按钮 */}
            <button
              type="submit"
              disabled={loading}
              className="btn-primary w-full py-3.5 rounded-xl text-white font-semibold text-base flex items-center justify-center gap-2"
            >
              {loading ? '处理中...' : mode === 'login' ? '登录' : inviteToken ? '加入团队' : '创建账号'}
              {!loading && (
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3" />
                </svg>
              )}
            </button>

            {/* 社交登录（仅登录） */}
            {mode === 'login' && (
              <>
                <div className="relative my-2">
                  <div className="absolute inset-0 flex items-center">
                    <div className="w-full border-t border-emerald-100" />
                  </div>
                  <div className="relative flex justify-center">
                    <span className="bg-transparent px-3 text-xs text-slate-400">或使用</span>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <button
                    type="button"
                    className="input-field rounded-xl py-3 flex items-center justify-center gap-2 text-slate-600 text-sm font-medium hover:text-slate-800 transition"
                  >
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M12 2C6.48 2 2 6.48 2 12c0 4.42 2.87 8.17 6.84 9.5.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.89 1.53 2.34 1.09 2.91.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.65 0 0 .84-.27 2.75 1.02.8-.22 1.65-.33 2.5-.33.85 0 1.7.11 2.5.33 1.91-1.29 2.75-1.02 2.75-1.02.55 1.38.2 2.4.1 2.65.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.69-4.57 4.94.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10.01 10.01 0 0 0 22 12c0-5.52-4.48-10-10-10Z" />
                    </svg>
                    GitHub
                  </button>
                  <button
                    type="button"
                    className="input-field rounded-xl py-3 flex items-center justify-center gap-2 text-slate-600 text-sm font-medium hover:text-slate-800 transition"
                  >
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M12.48 10.92v3.28h7.84c-.24 1.84-.853 3.187-1.787 4.133-1.147 1.147-2.933 2.4-6.053 2.4-4.827 0-8.6-3.893-8.6-8.72s3.773-8.72 8.6-8.72c2.6 0 4.507 1.027 5.907 2.347l2.307-2.307C18.747 1.44 16.133 0 12.48 0 5.867 0 .307 5.387.307 12s5.56 12 12.173 12c3.573 0 6.267-1.173 8.373-3.36 2.16-2.16 2.84-5.213 2.84-7.667 0-.76-.053-1.467-.173-2.053H12.48Z" />
                    </svg>
                    Google
                  </button>
                </div>
              </>
            )}
          </form>

          {/* 底部切换文字（邀请模式隐藏） */}
          {!inviteToken && (
            <p className="text-center text-sm text-slate-500 mt-6">
              {mode === 'login' ? (
                <>
                  还没有账号？
                  <button onClick={() => switchMode('register')} className="text-brand-600 hover:text-brand-700 font-semibold ml-1">
                    立即注册
                  </button>
                </>
              ) : (
                <>
                  已有账号？
                  <button onClick={() => switchMode('login')} className="text-brand-600 hover:text-brand-700 font-semibold ml-1">
                    立即登录
                  </button>
                </>
              )}
            </p>
          )}
        </div>
      </div>
    </>
  )
}

/** SVG 路径常量。 */
const ICONS = {
  user: 'M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 12 21.75c-2.676 0-5.216-.584-7.499-1.632Z',
  email:
    'M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75',
  lock: 'M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-6.75a2.25 2.25 0 0 0-2.25-2.25H6.75a2.25 2.25 0 0 0-2.25 2.25v6.75a2.25 2.25 0 0 0 2.25 2.25Z',
  eye: 'M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z',
  shield:
    'M9 12.75 11.25 15 15 9.75m-3-7.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285Z',
  features: [
    'm9 12.75 3 3m0 0 3-3m-3 3v-7.5M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
    'M9 12.75 11.25 15 15 9.75M21 12c0 1.268-.63 2.39-1.593 3.068a3.745 3.745 0 0 1-1.043 3.296 3.745 3.745 0 0 1-3.296 1.043A3.745 3.745 0 0 1 12 21c-1.268 0-2.39-.63-3.068-1.593a3.746 3.746 0 0 1-3.296-1.043 3.745 3.745 0 0 1-1.043-3.296A3.745 3.745 0 0 1 3 12c0-1.268.63-2.39 1.593-3.068a3.745 3.745 0 0 1 1.043-3.296 3.746 3.746 0 0 1 3.296-1.043A3.746 3.746 0 0 1 12 3c1.268 0 2.39.63 3.068 1.593a3.746 3.746 0 0 1 3.296 1.043 3.746 3.746 0 0 1 1.043 3.296A3.745 3.745 0 0 1 21 12Z',
    'M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456Z',
  ],
}
