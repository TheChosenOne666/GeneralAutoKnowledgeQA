/** 应用布局 — 左侧边栏 + 主内容区（按设计稿 SVG 图标）。*/

import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { useState } from 'react'
import type { Role } from '@/types'


interface MenuItem {
  to: string
  label: string
  icon: string
  roles: Role[]
}

const MENU: MenuItem[] = [
  { to: '/knowledge', label: '知识库', icon: 'M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25', roles: ['member', 'tenant_admin'] },
  { to: '/ai-config', label: 'AI模型配置', icon: 'M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456Z', roles: ['member', 'tenant_admin'] },
  { to: '/members', label: '成员管理', icon: 'M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Z', roles: ['tenant_admin'] },
  { to: '/audit', label: '审计日志', icon: 'M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 0 0 2.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 0 0-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 0 0 .75-.75 2.25 2.25 0 0 0-.1-.664m-5.8 0A2.251 2.251 0 0 1 13.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25Z', roles: ['tenant_admin', 'super_admin'] },
  { to: '/chat', label: '对话', icon: 'M2.25 12.76c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.184-4.183a1.14 1.14 0 0 1 .778-.332 48.294 48.294 0 0 0 5.83-.498c1.585-.233 2.708-1.626 2.708-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z', roles: ['member', 'tenant_admin', 'super_admin'] },
]

const ROLE_LABELS: Record<string, string> = {
  super_admin: '平台超管',
  tenant_admin: '租户管理员',
  member: '普通成员',
}

export default function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const isChat = location.pathname === '/chat' || location.pathname.startsWith('/chat/')

  // 当前会话列表（本地 mock，后续可接入 /api/chat/conversations）
  const [sessions] = useState([
    { id: '1', title: '探索我的数字身份来源', group: '近7天' },
    { id: '2', title: '关于我的身份介绍', group: '近7天' },
    { id: '3', title: '用户身份询问', group: '近7天' },
    { id: '4', title: '初次问候', group: '近7天' },
    { id: '5', title: '初次问候', group: '近7天' },
    { id: '6', title: '初次问候', group: '近7天' },
  ])
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null)

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const visibleMenu = MENU.filter((item) => user && item.roles.includes(user.role))

  return (
    <div className="flex h-screen">
      {/* 侧边栏 */}
      <div className="w-60 bg-white border-r border-emerald-100 flex flex-col flex-shrink-0">
        {/* Logo */}
        <div className="h-16 flex items-center gap-2.5 px-4 border-b border-emerald-50">
          <img src="/logo.png" alt="熊答" className="w-10 h-10 rounded-lg object-cover" />
          <span className="text-lg font-extrabold tracking-tight text-slate-800">熊答</span>
        </div>

        {/* 菜单 */}
        <nav className="p-2 space-y-1 flex-1">
          {visibleMenu.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors ${
                  isActive
                    ? 'bg-emerald-50 text-emerald-600 border-l-[3px] border-emerald-500'
                    : 'text-slate-500 hover:bg-emerald-50/50 hover:text-emerald-600'
                }`
              }
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d={item.icon} />
              </svg>
              {item.label}
            </NavLink>
          ))}
        </nav>

        {/* 对话历史（仅在对话页显示） */}
        {isChat && (
          <div className="flex-1 flex flex-col min-h-0 border-t border-emerald-50">
            <div className="h-10 flex items-center justify-between px-4">
              <div className="flex items-center gap-2 text-emerald-600 font-semibold text-sm">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 12.76c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.184-4.183a1.14 1.14 0 0 1 .778-.332 48.294 48.294 0 0 0 5.83-.498c1.585-.233 2.708-1.626 2.708-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z" />
                </svg>
                历史记录
              </div>
              <button
                onClick={() => setSelectedSessionId(null)}
                className="w-6 h-6 rounded-md flex items-center justify-center text-emerald-600 hover:bg-emerald-50 transition"
                title="新建对话"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
                </svg>
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-3 pb-3">
              <div className="text-xs text-slate-400 mb-2 px-2">近7天</div>
              <div className="space-y-1">
                {sessions.map((session) => (
                  <button
                    key={session.id}
                    onClick={() => setSelectedSessionId(session.id)}
                    className={`w-full text-left px-3 py-2 rounded-lg text-sm truncate transition ${
                      selectedSessionId === session.id
                        ? 'bg-emerald-50 text-emerald-700 font-medium'
                        : 'text-slate-600 hover:bg-slate-50'
                    }`}
                  >
                    {session.title}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* 用户信息 */}
        <div className="p-3 border-t border-emerald-50">
          <div className="flex items-center gap-3 px-2">
            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-brand-500 to-teal-400 flex items-center justify-center text-white text-sm font-bold">
              {user?.name?.[0] || '?'}
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-sm font-medium text-slate-700 truncate">{user?.name}</div>
              <div className="text-xs text-slate-400">{user ? ROLE_LABELS[user.role] : ''}</div>
            </div>
            <button
              onClick={handleLogout}
              className="text-slate-400 hover:text-red-500 transition-colors"
              title="退出登录"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15M12 9l-3 3m0 0 3 3m-3-3h12.75" />
              </svg>
            </button>
          </div>
        </div>
      </div>

      {/* 主内容区 */}
      <div className="flex-1 overflow-hidden bg-emerald-50/30">
        <Outlet />
      </div>
    </div>
  )
}
