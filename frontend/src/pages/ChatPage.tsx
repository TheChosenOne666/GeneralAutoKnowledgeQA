/** 问答页骨架。*/

export default function ChatPage() {
  return (
    <div className="flex flex-col h-full">
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center px-6">
        <h3 className="text-base font-bold text-slate-800">AI 问答</h3>
      </div>
      <div className="flex-1 flex flex-col items-center justify-center p-6">
        <img src="/logo.png" alt="熊答" className="w-20 h-20 rounded-2xl object-cover mb-6" />
        <h1 className="text-2xl font-bold text-slate-800 mb-2">基于知识库内容问答</h1>
        <p className="text-slate-500 mb-8">你可以这样问我</p>
        <div className="flex flex-wrap gap-3 justify-center mb-8">
          {['公司年假政策是什么？', '新员工入职流程是怎样的？', '报销审批流程是什么？'].map((q) => (
            <button
              key={q}
              className="px-4 py-2.5 rounded-full border border-emerald-200 bg-white text-slate-600 text-sm font-medium hover:border-brand-400 hover:text-emerald-600 transition cursor-pointer"
            >
              {q}
            </button>
          ))}
        </div>
        <div className="w-full max-w-2xl">
          <div className="bg-white rounded-2xl border border-emerald-200 shadow-sm p-2">
            <textarea
              placeholder="输入问题，将基于知识库回答"
              className="w-full px-4 py-3 bg-transparent text-sm text-slate-700 placeholder-slate-400 outline-none resize-none"
              rows={2}
            />
            <div className="flex items-center justify-between px-2 pb-1">
              <div className="flex items-center gap-2">
                <select className="text-xs text-slate-500 bg-transparent outline-none">
                  <option>deepseek-v3.2</option>
                  <option>doubao-pro</option>
                </select>
              </div>
              <button className="w-8 h-8 rounded-lg bg-emerald-500 text-white flex items-center justify-center hover:bg-brand-600 transition">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5" />
                </svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
