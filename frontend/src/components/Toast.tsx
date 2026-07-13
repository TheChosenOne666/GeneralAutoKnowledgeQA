/** 全局顶部提示（Toast）— 固定在页面顶部居中，成功为绿色横幅、失败为红色。
 *  通过 ToastProvider 挂在应用根部，任意页面可用 useToast() 触发。*/

import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'

type ToastType = 'success' | 'error'

interface ToastItem {
  id: number
  type: ToastType
  msg: string
}

interface ToastContextValue {
  /** 弹出一条提示，type 默认 success。*/
  notify: (msg: string, type?: ToastType) => void
  success: (msg: string) => void
  error: (msg: string) => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

const AUTO_DISMISS_MS = 3000

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])

  const notify = useCallback((msg: string, type: ToastType = 'success') => {
    const id = Date.now() + Math.random()
    setToasts((prev) => [...prev, { id, type, msg }])
    window.setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, AUTO_DISMISS_MS)
  }, [])

  const success = useCallback((msg: string) => notify(msg, 'success'), [notify])
  const error = useCallback((msg: string) => notify(msg, 'error'), [notify])

  return (
    <ToastContext.Provider value={{ notify, success, error }}>
      {children}
      <div className="fixed top-0 left-0 right-0 z-[100] flex flex-col items-center gap-2 pt-4 pointer-events-none">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`flex items-center gap-2 px-5 py-2.5 rounded-lg shadow-lg text-sm font-semibold fade-in ${
              t.type === 'success' ? 'bg-emerald-500 text-white' : 'bg-red-500 text-white'
            }`}
          >
            {t.type === 'success' ? (
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 12.75 6 6 9-13.5" />
              </svg>
            ) : (
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m0 3.75h.008M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
              </svg>
            )}
            {t.msg}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

/** 获取全局提示控制器。必须在 ToastProvider 内使用。*/
export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast 必须在 ToastProvider 内使用')
  return ctx
}
