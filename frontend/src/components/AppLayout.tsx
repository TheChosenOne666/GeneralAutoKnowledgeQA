/** 应用布局 — 左侧边栏 + 主内容区（按设计稿 SVG 图标）。*/

import { Fragment, useMemo } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { ChatProvider, useChat } from '@/context/ChatContext'
import { chatApi } from '@/api/chat'
import type { Conversation, Role } from '@/types'


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

/** 按更新时间分组：今天 / 近7天 / 更早。*/
function groupLabel(time?: string): string {
  if (!time) return '更早'
  const d = new Date(time).getTime()
  if (Number.isNaN(d)) return '更早'
  const diffDays = (Date.now() - d) / 86_400_000
  if (diffDays < 1) return '今天'
  if (diffDays < 7) return '近7天'
  return '更早'
}

export default function AppLayout() {
  return (
    <ChatProvider>
      <AppLayoutInner />
    </ChatProvider>
  )
}

function AppLayoutInner() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const { conversations, activeId, setActiveId, refresh } = useChat()
  const isChat = location.pathname === '/chat' || location.pathname.startsWith('/chat/')

  const grouped = useMemo(() => {
    const map: Record<string, Conversation[]> = {}
    for (const c of conversations) {
      const g = groupLabel(c.updateTime)
      ;(map[g] ||= []).push(c)
    }
    return Object.entries(map)
  }, [conversations])

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  // 新建对话：仅切换到空白窗口，不立即落库（发消息时后端才建会话，避免连点产生空会话）
  const handleNewChat = () => setActiveId(null)

  const handleRename = async (c: Conversation) => {
    const title = window.prompt('重命名会话', c.title)
    if (!title || !title.trim()) return
    try {
      await chatApi.renameConversation(c.id, title.trim())
      await refresh()
    } catch {
      window.alert('重命名失败')
    }
  }

  const handleDelete = async (c: Conversation) => {
    if (!window.confirm(`确定删除会话「${c.title}」？此操作不可恢复。`)) return
    try {
      await chatApi.deleteConversation(c.id)
      if (activeId === c.id) setActiveId(null)
      await refresh()
    } catch {
      window.alert('删除失败')
    }
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
                onClick={handleNewChat}
                className="w-6 h-6 rounded-md flex items-center justify-center text-emerald-600 hover:bg-emerald-50 transition"
                title="新建对话"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
                </svg>
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-3 pb-3">
              {conversations.length === 0 ? (
                <p className="text-xs text-slate-400 px-2 pt-2">暂无对话，发送消息开始新会话</p>
              ) : (
                grouped.map(([group, items]) => (
                  <Fragment key={group}>
                    <div className="text-xs text-slate-400 mb-2 mt-2 px-2 first:mt-0">{group}</div>
                    <div className="space-y-1">
                      {items.map((c) => (
                        <div
                          key={c.id}
                          className={`group/item relative flex items-center rounded-lg transition ${
                            activeId === c.id ? 'bg-emerald-50' : 'hover:bg-slate-50'
                          }`}
                        >
                          <button
                            onClick={() => setActiveId(c.id)}
                            className={`flex-1 text-left px-3 py-2 text-sm truncate ${
                              activeId === c.id ? 'text-emerald-700 font-medium' : 'text-slate-600'
                            }`}
                            title={c.title}
                          >
                            {c.title}
                          </button>
                          <div className="hidden group-hover/item:flex items-center gap-0.5 pr-1.5">
                            <button
                              onClick={() => handleRename(c)}
                              className="w-6 h-6 rounded flex items-center justify-center text-slate-400 hover:text-brand-600 hover:bg-emerald-100 transition"
                              title="重命名"
                            >
                              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 3h4.75" />
                              </svg>
                            </button>
                            <button
                              onClick={() => handleDelete(c)}
                              className="w-6 h-6 rounded flex items-center justify-center text-slate-400 hover:text-red-500 hover:bg-red-50 transition"
                              title="删除"
                            >
                              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0" />
                              </svg>
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  </Fragment>
                ))
              )}
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
