/** 路由配置。*/

import { createBrowserRouter, Navigate, useNavigate, useRouteError } from 'react-router-dom'
import { getToken } from '@/api/client'
import AppLayout from '@/components/AppLayout'
import AuthPage from '@/pages/AuthPage'
import ChatPage from '@/pages/ChatPage'
import KnowledgeBasePage from '@/pages/KnowledgeBasePage'
import AIConfigPage from '@/pages/AIConfigPage'
import MembersPage from '@/pages/MembersPage'
import AuditLogPage from '@/pages/AuditLogPage'
import RolePermissionPage from '@/pages/RolePermissionPage'

function AuthGuard({ children }: { children: React.ReactNode }) {
  if (!getToken()) return <Navigate to="/login" replace />
  return <>{children}</>
}

/** 路由级错误兜底：避免裸奔的 React Router 错误页，提供返回入口。*/
function RouteError() {
  const error = useRouteError() as { status?: number; statusText?: string; message?: string }
  const navigate = useNavigate()
  const status = error?.status ?? ''
  const detail = error?.statusText || error?.message || '页面加载出错'
  return (
    <div className="flex flex-col items-center justify-center h-screen bg-emerald-50/30 text-center px-6">
      <div className="text-5xl font-extrabold text-emerald-500 mb-2">{status || '出错'}</div>
      <p className="text-slate-500 mb-6">{detail}</p>
      <button
        onClick={() => navigate('/chat')}
        className="px-5 py-2.5 rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold hover:opacity-90 transition"
      >
        返回对话
      </button>
    </div>
  )
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <AuthPage />,
    errorElement: <RouteError />,
  },
  {
    path: '/',
    element: (
      <AuthGuard>
        <AppLayout />
      </AuthGuard>
    ),
    errorElement: <RouteError />,
    children: [
      { index: true, element: <Navigate to="/chat" replace /> },
      { path: 'chat', element: <ChatPage /> },
      { path: 'knowledge', element: <KnowledgeBasePage /> },
      { path: 'ai-config', element: <AIConfigPage /> },
      { path: 'members', element: <MembersPage /> },
      { path: 'audit', element: <AuditLogPage /> },
      { path: 'role-permission', element: <RolePermissionPage /> },
    ],
  },
])
