/** 应用布局 — 左侧边栏 + 主内容区（按设计稿 SVG 图标）。*/

import { useMemo, useState, useEffect } from 'react'
import { NavLink, useLocation, useNavigate, useOutlet } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { ChatProvider, useChat } from '@/context/ChatContext'
import { TenantProvider, useTenant } from '@/context/TenantContext'
import { chatApi } from '@/api/chat'
import { auditApi } from '@/api/audit'
import { knowledgeApi } from '@/api/knowledge'
import { GlobalSearch } from '@/components/GlobalSearch'
import type { Conversation, KnowledgeBase, Role } from '@/types'


interface MenuItem {
  to: string
  label: string
  icon: string
  roles: Role[]
}

const MENU: MenuItem[] = [
  { to: '/knowledge', label: '知识库', icon: 'M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25', roles: ['member', 'tenant_admin'] },
  { to: '/ai-config', label: 'AI模型配置', icon: 'M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456Z', roles: ['member', 'tenant_admin', 'super_admin'] },
  { to: '/members', label: '成员管理', icon: 'M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Z', roles: ['tenant_admin'] },
  { to: '/audit', label: '审计日志', icon: 'M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 0 0 2.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 0 0-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 0 0 .75-.75 2.25 2.25 0 0 0-.1-.664m-5.8 0A2.251 2.251 0 0 1 13.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25Z', roles: ['tenant_admin', 'super_admin'] },
  { to: '/role-permission', label: '角色权限', icon: 'M9 12.75 9.75 15h1.5l.75-2.25M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z', roles: ['member', 'tenant_admin', 'super_admin'] },
  { to: '/tenant', label: '租户管理', icon: 'M2.25 21h19.5m-18-18v18m10.5-18v18m6-13.5V21M6.75 6.75h.75V4.5h-.75v2.25Zm0 0H4.5v2.25h2.25V6.75Zm0 9h.75v-2.25h-.75V15.75Zm0 0H4.5v2.25h2.25v-2.25ZM15 6.75h.75V4.5H15v2.25Zm0 0h-2.25v2.25H15V6.75Zm0 9h.75v-2.25H15V15.75Zm0 0h-2.25v2.25H15v-2.25Z', roles: ['super_admin'] },
  { to: '/chat', label: '对话', icon: 'M2.25 12.76c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.184-4.183a1.14 1.14 0 0 1 .778-.332 48.294 48.294 0 0 0 5.83-.498c1.585-.233 2.708-1.626 2.708-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z', roles: ['member', 'tenant_admin', 'super_admin'] },
]

const ROLE_LABELS: Record<string, string> = {
  super_admin: '平台超管',
  tenant_admin: '租户管理员',
  member: '普通成员',
}

const DAY = 86_400_000

/** 取某天的 0 点（本地时区）。*/
function startOfDay(d: Date): Date {
  const x = new Date(d)
  x.setHours(0, 0, 0, 0)
  return x
}

/** 历史记录分组定义（从新到旧），collapsible 为 true 表示默认折叠、可展开。*/
interface HistoryGroupDef {
  key: string
  label: string
  collapsible: boolean
}

/** 根据更新时间归入唯一分组（互斥，避免同一会话重复出现于多个分组）。*/
function historyGroupOf(time?: string): string {
  if (!time) return 'older'
  const t = new Date(time).getTime()
  if (Number.isNaN(t)) return 'older'
  const now = new Date()
  const today0 = startOfDay(now)
  const yesterday0 = startOfDay(new Date(now.getTime() - DAY))
  const daysAgo = (n: number) => new Date(today0.getTime() - n * DAY).getTime()
  const monthsAgo = (n: number) => {
    const d = new Date()
    d.setMonth(d.getMonth() - n)
    return d.getTime()
  }
  if (t >= today0.getTime()) return 'today'
  if (t >= yesterday0.getTime()) return 'yesterday'
  if (t >= daysAgo(2)) return 'last3'
  if (t >= daysAgo(6)) return 'last7'
  if (t >= monthsAgo(1)) return 'm1'
  if (t >= monthsAgo(3)) return 'm3'
  if (t >= monthsAgo(6)) return 'm6'
  return 'older'
}

/** 历史记录分组顺序与展示（7 天以上默认折叠）。*/
const HISTORY_GROUPS: HistoryGroupDef[] = [
  { key: 'today', label: '今天', collapsible: false },
  { key: 'yesterday', label: '昨天', collapsible: false },
  { key: 'last3', label: '近3天', collapsible: false },
  { key: 'last7', label: '近7天', collapsible: false },
  { key: 'm1', label: '近1个月内', collapsible: true },
  { key: 'm3', label: '近3个月内', collapsible: true },
  { key: 'm6', label: '近半年内', collapsible: true },
  { key: 'older', label: '更早', collapsible: true },
]

export default function AppLayout() {
  return (
    <TenantProvider>
      <ChatProvider>
        <AppLayoutInner />
      </ChatProvider>
    </TenantProvider>
  )
}

function AppLayoutInner() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const outlet = useOutlet()
  const { currentTenantId, tenants, setTenant } = useTenant()
  const { conversations, activeId, setActiveId, refresh } = useChat()
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const isChat = location.pathname === '/chat' || location.pathname.startsWith('/chat/')

  // 加载知识库列表（用于全局搜索本地过滤）
  useEffect(() => {
    knowledgeApi.list().then(setKnowledgeBases).catch(() => {})
  }, [])

  // 平台超管可访问：自身专属菜单 + 租户管理员菜单（切进某租户后复用其知识库/成员/AI配置页）
  const effectiveRoles: Role[] = user?.role === 'super_admin'
    ? ['super_admin', 'tenant_admin']
    : user?.role
      ? [user.role]
      : []
  const visibleMenu = MENU.filter((item) => user && item.roles.some((r) => effectiveRoles.includes(r)))

  // 按时间分组（仅展示有会话的分组，顺序见 HISTORY_GROUPS），7 天以上默认折叠
  const grouped = useMemo(() => {
    const map: Record<string, Conversation[]> = {}
    for (const c of conversations) {
      const g = historyGroupOf(c.updateTime)
      ;(map[g] ||= []).push(c)
    }
    return HISTORY_GROUPS.filter((grp) => (map[grp.key]?.length ?? 0) > 0).map(
      (grp) => [grp, map[grp.key]] as const,
    )
  }, [conversations])

  const handleLogout = () => {
    // 记录登出审计（失败不影响登出）
    auditApi.logout().catch(() => {})
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

  return (
    <div className="flex h-screen">
      {/* 侧边栏 */}
      <div className="w-60 bg-white border-r border-emerald-100 flex flex-col flex-shrink-0">
        {/* Logo */}
        <div className="h-16 flex items-center gap-2.5 px-4 border-b border-emerald-50">
          <img src="/logo.png" alt="熊答" className="w-10 h-10 rounded-lg object-cover" />
          <span className="text-lg font-extrabold tracking-tight text-slate-800">熊答</span>
        </div>

        {/* 平台超管租户切换器（对齐 业界 TenantSelector（租户切换））：切进某租户后当作该租户 admin 操作 */}
        {user?.role === 'super_admin' && (
          <div className="px-3 pb-1 pt-2">
            <div className="text-[11px] text-slate-400 mb-1">当前操作租户</div>
            <select
              value={currentTenantId == null ? '' : String(currentTenantId)}
              onChange={(e) => {
                const t = tenants.find((x) => String(x.id) === e.target.value)
                if (t) setTenant(t)
              }}
              className="w-full rounded-md border border-emerald-100 bg-white px-2 py-1.5 text-sm text-slate-700 focus:outline-none focus:ring-1 focus:ring-emerald-400"
            >
              {tenants.map((t) => (
                <option key={String(t.id)} value={String(t.id)}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>
        )}

        {/* 全局搜索框（放在导航菜单上方） */}
        <div className="px-3 py-2">
          <GlobalSearch
            conversations={conversations}
            knowledgeBases={knowledgeBases}
            onSelectConversation={(id) => setActiveId(id)}
            onSelectKnowledgeBase={() => navigate('/knowledge')}
            onSelectDocument={() => navigate('/knowledge')}
            onSelectMessage={(m) => setActiveId(m.conversationId)}
          />
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
                grouped.map(([grp, items]) => (
                  <HistoryGroup
                    key={grp.key}
                    label={grp.label}
                    collapsible={grp.collapsible}
                    items={items}
                    activeId={activeId}
                    onSelect={(id) => setActiveId(id)}
                    onRename={handleRename}
                    onDelete={handleDelete}
                  />
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

      {/* 主内容区：切换租户时以 tenantId 为 key 重挂，触发各业务页重新加载该租户数据 */}
      <div
        key={currentTenantId == null ? 'none' : String(currentTenantId)}
        className="flex-1 overflow-hidden bg-emerald-50/30"
      >
        {outlet}
      </div>
    </div>
  )
}

/** 历史记录分组：标题灰色；collapsible 为 true 时显示箭头、默认折叠，点击展开。*/
function HistoryGroup({
  label,
  collapsible,
  items,
  activeId,
  onSelect,
  onRename,
  onDelete,
}: {
  label: string
  collapsible: boolean
  items: Conversation[]
  activeId: string | null
  onSelect: (id: string) => void
  onRename: (c: Conversation) => void
  onDelete: (c: Conversation) => void
}) {
  const [open, setOpen] = useState(!collapsible)
  return (
    <div>
      <button
        type="button"
        onClick={() => collapsible && setOpen((v) => !v)}
        className={`w-full flex items-center gap-1 text-xs text-slate-400 mb-2 mt-2 px-2 first:mt-0 ${
          collapsible ? 'cursor-pointer hover:text-slate-500' : 'cursor-default'
        }`}
      >
        {collapsible && (
          <svg
            className={`w-3 h-3 transition-transform ${open ? 'rotate-90' : ''}`}
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
          </svg>
        )}
        <span>{label}</span>
        {collapsible && <span className="text-slate-300">（{items.length}）</span>}
      </button>
      {open && (
        <div className="space-y-1">
          {items.map((c) => (
            <ConvItem
              key={c.id}
              c={c}
              active={activeId === c.id}
              onSelect={onSelect}
              onRename={onRename}
              onDelete={onDelete}
            />
          ))}
        </div>
      )}
    </div>
  )
}

/** 单条会话项（标题 + 悬停重命名/删除）。*/
function ConvItem({
  c,
  active,
  onSelect,
  onRename,
  onDelete,
}: {
  c: Conversation
  active: boolean
  onSelect: (id: string) => void
  onRename: (c: Conversation) => void
  onDelete: (c: Conversation) => void
}) {
  return (
    <div
      className={`group/item relative flex items-center rounded-lg transition ${
        active ? 'bg-emerald-50' : 'hover:bg-slate-50'
      }`}
    >
      <button
        onClick={() => onSelect(c.id)}
        className={`flex-1 text-left px-3 py-2 text-sm truncate ${
          active ? 'text-emerald-700 font-medium' : 'text-slate-600'
        }`}
        title={c.title}
      >
        {c.title}
      </button>
      <div className="hidden group-hover/item:flex items-center gap-0.5 pr-1.5">
        <button
          onClick={() => onRename(c)}
          className="w-6 h-6 rounded flex items-center justify-center text-slate-400 hover:text-brand-600 hover:bg-emerald-100 transition"
          title="重命名"
        >
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 3h4.75" />
          </svg>
        </button>
        <button
          onClick={() => onDelete(c)}
          className="w-6 h-6 rounded flex items-center justify-center text-slate-400 hover:text-red-500 hover:bg-red-50 transition"
          title="删除"
        >
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0" />
          </svg>
        </button>
      </div>
    </div>
  )
}
