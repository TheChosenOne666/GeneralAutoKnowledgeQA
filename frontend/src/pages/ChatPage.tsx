/** 问答页 — 按设计稿 02-chat.html + SSE 流式渲染。*/

import { useRef, useState } from 'react'
import { chatApi } from '@/api/chat'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

const SUGGESTIONS = [
  '熊答是什么平台？',
  '如何上传文档到知识库？',
  '公司年假政策是什么？',
  '新员工入职流程是怎样的？',
]

export default function ChatPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [conversationId, setConversationId] = useState<string | null>(null)

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
    <div className="flex-1 flex flex-col relative overflow-hidden bg-white">
      <div className="flex-1 overflow-auto px-6">
        {messages.length === 0 ? (
          <div className="max-w-3xl mx-auto pt-24 text-center pb-64">
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
          <div className="max-w-3xl mx-auto py-6 space-y-6 pb-64">
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

      <div className="absolute bottom-0 left-0 right-0 px-6 py-5 bg-gradient-to-t from-white via-white to-transparent">
        <div className="max-w-3xl mx-auto">
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
                <button className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 text-xs font-medium hover:bg-emerald-50 hover:text-brand-700 cursor-pointer transition">
                  <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z" /></svg>
                  智能推理
                </button>
                <button className="w-8 h-8 rounded-lg flex items-center justify-center bg-emerald-500 text-white hover:bg-brand-600 cursor-pointer shadow-sm transition" title="发送" onClick={() => sendMessage(input)} disabled={streaming || !input.trim()}>
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5" /></svg>
                </button>
              </div>
              <button className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 text-slate-600 text-xs font-medium hover:bg-emerald-50 hover:text-brand-700 cursor-pointer transition">
                <span className="w-2 h-2 rounded-full bg-emerald-500" />
                deepseek-v3.2
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
