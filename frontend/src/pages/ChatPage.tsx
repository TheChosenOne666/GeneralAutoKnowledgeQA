/** 问答页 — 左侧会话历史 + 右侧问答区（按用户截图）。 */

import { useRef, useState } from 'react'
import { chatApi } from '@/api/chat'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}


const SUGGESTIONS = [
  '文档"这是一个测试文档.docx"的具体内容是什么？',
  '如何编辑首页文档中的愿景和目标部分？',
  'Weknora是什么平台或工具？',
  '如何利用img标签的onerror属性执行JavaScript代码？',
  '"Hello.txt"文档的内容是什么？',
  '测试数据.xlsx中SKU0079的库存量是多少？',
]

export default function ChatPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [conversationId, setConversationId] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  const sendMessage = async (text: string) => {
    if (!text.trim() || streaming) return
    setInput('')
    setStreaming(true)
    setMessages((prev) => [...prev, { role: 'user', content: text }, { role: 'assistant', content: '' }])

    try {
      const reader = await chatApi.streamChat(text, conversationId || undefined)
      const decoder = new TextDecoder()
      let buffer = ''
      let aiContent = ''
      let convId = conversationId

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (!data || data === '[DONE]') continue
          try {
            const parsed = JSON.parse(data)
            if (parsed.conversation_id && !convId) {
              convId = parsed.conversation_id
              setConversationId(convId)
            }
            if (parsed.content) {
              aiContent += parsed.content
              setMessages((prev) => {
                const next = [...prev]
                next[next.length - 1] = { role: 'assistant', content: aiContent }
                return next
              })
            }
          } catch {
            // 非 JSON 行跳过
          }
        }
      }
    } catch (err) {
      setMessages((prev) => {
        const next = [...prev]
        next[next.length - 1] = { role: 'assistant', content: `错误：${err instanceof Error ? err.message : '请求失败'}` }
        return next
      })
    } finally {
      setStreaming(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage(input)
    }
  }

  return (
    <div className="h-full flex flex-col relative overflow-hidden bg-white">
      {/* 主内容区 */}
      <div ref={scrollRef} className="flex-1 overflow-auto px-6">
          {messages.length === 0 ? (
            /* 欢迎区 */
            <div className="max-w-3xl mx-auto pt-24 text-center">
              <h1 className="text-2xl font-bold text-slate-800 mb-3">基于知识库内容问答 – AI 问答</h1>
              <p className="text-sm text-slate-400 mb-8">你可以这样问我</p>
              <div className="flex flex-wrap justify-center gap-3">
                {SUGGESTIONS.map((q) => (
                  <button
                    key={q}
                    onClick={() => sendMessage(q)}
                    className="px-4 py-2.5 rounded-full border border-emerald-200 bg-white text-slate-600 text-sm font-medium cursor-pointer transition-all hover:border-brand-400 hover:text-emerald-600 hover:bg-emerald-50"
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            /* 消息列表 */
            <div className="max-w-3xl mx-auto pt-6 space-y-6">
              {messages.map((msg, i) => (
                <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                  {msg.role === 'assistant' && (
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-brand-500 to-teal-400 flex items-center justify-center text-white text-sm font-bold mr-3 flex-shrink-0">熊</div>
                  )}
                  <div className={`max-w-[80%] px-4 py-3 rounded-2xl text-sm whitespace-pre-wrap ${msg.role === 'user' ? 'bg-gradient-to-r from-brand-600 to-brand-500 text-white rounded-tr-sm' : 'bg-emerald-50 text-slate-700 rounded-tl-sm'}`}>
                    {msg.content || (streaming && i === messages.length - 1 ? '思考中...' : '')}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 底部输入区（固定底部） */}
        <div className="flex-shrink-0 px-6 py-5 bg-white border-t border-emerald-100">
          <div className="max-w-3xl mx-auto">
            {/* 知识库标签 */}
            <div className="flex gap-2 mb-2">
              <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-50 text-brand-700 text-xs font-medium border border-emerald-200">
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25" />
                </svg>
                测试
                <button className="ml-1 hover:text-brand-900">
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                  </svg>
                </button>
              </span>
            </div>

            {/* 输入框 */}
            <div className="bg-white border border-emerald-200 rounded-2xl shadow-lg shadow-emerald-100/50 overflow-hidden focus-within:border-brand-400 focus-within:ring-2 focus-within:ring-brand-500/20 transition-all">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="输入问题，将基于知识库和网络搜索回答"
                className="w-full px-5 py-4 bg-transparent text-sm text-slate-700 placeholder-slate-400 outline-none resize-none"
                rows={2}
                disabled={streaming}
              />
              <div className="px-3 pb-3 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  {/* 智能推理 */}
                  <button className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 text-xs font-medium hover:bg-emerald-50 hover:text-brand-700 cursor-pointer transition">
                    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z" />
                    </svg>
                    智能推理
                    <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                    </svg>
                  </button>
                  {/* 图片 */}
                  <button className="w-8 h-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-brand-600 hover:bg-emerald-50 cursor-pointer transition" title="图片">
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909m-18 3.75h16.5a2.25 2.25 0 0 0 2.25-2.25V6a2.25 2.25 0 0 0-2.25-2.25H3.75A2.25 2.25 0 0 0 1.5 6v12a2.25 2.25 0 0 0 2.25 2.25Z" />
                    </svg>
                  </button>
                  {/* 附件 */}
                  <button className="w-8 h-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-brand-600 hover:bg-emerald-50 cursor-pointer transition" title="附件">
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="m18.375 12.739-7.693 7.693a4.5 4.5 0 0 1-6.364-6.364l10.94-10.94A3 3 0 1 1 19.5 7.372L8.552 18.32m.009-.01-.01.01m5.699-9.941-7.667 7.667" />
                    </svg>
                  </button>
                </div>
                <div className="flex items-center gap-2">
                  {/* 模型选择 */}
                  <button className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 text-xs font-medium hover:bg-emerald-50 hover:text-brand-700 cursor-pointer transition">
                    <span className="w-2 h-2 rounded-full bg-emerald-500" />
                    deepseek-v3.2
                    <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                    </svg>
                  </button>
                  {/* 发送 */}
                  <button
                    onClick={() => sendMessage(input)}
                    disabled={streaming || !input.trim()}
                    className="w-8 h-8 rounded-lg flex items-center justify-center bg-emerald-500 text-white hover:bg-brand-600 cursor-pointer shadow-sm transition disabled:opacity-50"
                    title="发送"
                  >
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5" />
                    </svg>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
    </div>
  )
}
