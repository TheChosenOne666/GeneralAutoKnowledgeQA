/** 路由配置。*/

import { createBrowserRouter, Navigate } from 'react-router-dom'
import { getToken } from '@/api/client'
import AppLayout from '@/components/AppLayout'
import AuthPage from '@/pages/AuthPage'
import ChatPage from '@/pages/ChatPage'
import KnowledgeBasePage from '@/pages/KnowledgeBasePage'
import AIConfigPage from '@/pages/AIConfigPage'
import MembersPage from '@/pages/MembersPage'
import AuditLogPage from '@/pages/AuditLogPage'

function AuthGuard({ children }: { children: React.ReactNode }) {
  if (!getToken()) return <Navigate to="/login" replace />
  return <>{children}</>
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <AuthPage />,
  },
  {
    path: '/',
    element: (
      <AuthGuard>
        <AppLayout />
      </AuthGuard>
    ),
    children: [
      { index: true, element: <Navigate to="/chat" replace /> },
      { path: 'chat', element: <ChatPage /> },
      { path: 'knowledge', element: <KnowledgeBasePage /> },
      { path: 'ai-config', element: <AIConfigPage /> },
      { path: 'members', element: <MembersPage /> },
      { path: 'audit', element: <AuditLogPage /> },
    ],
  },
])
