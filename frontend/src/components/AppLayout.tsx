/** 应用布局 — 左侧边栏 + 主内容区。*/

import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import type { Role } from '@/types'

interface MenuItem {
  to: string
  label: string
  icon: string
  roles: Role[]
}

const MENU: MenuItem[] = [
  { to: '/chat', label: '对话', icon: '💬', roles: ['member', 'tenant_admin', 'super_admin'] },
  { to: '/knowledge', label: '知识库', icon: '📚', roles: ['member', 'tenant_admin'] },
  { to: '/ai-config', label: 'AI模型配置', icon: '⚙️', roles: ['member', 'tenant_admin'] },
  { to: '/members', label: '成员管理', icon: '👥', roles: ['tenant_admin'] },
  { to: '/audit', label: '审计日志', icon: '📋', roles: ['tenant_admin', 'super_admin'] },
]

const ROLE_LABELS: Record<string, string> = {
  super_admin: '平台超管',
  tenant_admin: '租户管理员',
  member: '普通成员',
}

export default function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

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
              <span className="text-lg">{item.icon}</span>
              {item.label}
            </NavLink>
          ))}
        </nav>

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
