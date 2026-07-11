/** AI 模型配置页骨架。*/

export default function AIConfigPage() {
  return (
    <div className="flex flex-col h-full">
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6">
        <h3 className="text-base font-bold text-slate-800">AI 模型配置</h3>
        <button className="px-5 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg hover:shadow-lg transition">
          保存配置
        </button>
      </div>
      <div className="flex-1 overflow-auto p-6">
        {/* 当前状态 */}
        <div className="bg-gradient-to-r from-brand-50 to-teal-50 border border-brand-200 rounded-2xl p-5 mb-6 flex gap-8">
          <div className="flex items-center gap-2.5">
            <div className="w-2.5 h-2.5 rounded-full bg-emerald-500" />
            <div>
              <div className="text-xs text-slate-500 font-medium">LLM</div>
              <div className="text-sm text-slate-800 font-bold">火山方舟 · 豆包Pro</div>
            </div>
          </div>
          <div className="flex items-center gap-2.5">
            <div className="w-2.5 h-2.5 rounded-full bg-emerald-500" />
            <div>
              <div className="text-xs text-slate-500 font-medium">Embedding</div>
              <div className="text-sm text-slate-800 font-bold">Doubao-embedding</div>
            </div>
          </div>
          <div className="flex items-center gap-2.5">
            <div className="w-2.5 h-2.5 rounded-full bg-slate-300" />
            <div>
              <div className="text-xs text-slate-500 font-medium">Rerank</div>
              <div className="text-sm text-slate-400 font-bold">未配置</div>
            </div>
          </div>
        </div>

        {/* 配置卡片 */}
        <div className="grid grid-cols-2 gap-6">
          {['LLM 大语言模型', 'Embedding 向量化模型', 'Rerank 重排序模型'].map((title, i) => (
            <div key={title} className="bg-white border border-emerald-100 rounded-2xl p-6">
              <h4 className="text-sm font-bold text-slate-800 mb-4">{title}</h4>
              <div className="space-y-3">
                <div>
                  <label className="block text-xs text-slate-500 mb-1">提供商</label>
                  <select className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700">
                    <option>火山方舟</option>
                    <option>OpenAI</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-slate-500 mb-1">模型</label>
                  <input className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700" defaultValue={i === 0 ? 'doubao-pro' : i === 1 ? 'doubao-embedding' : ''} placeholder="模型名称" />
                </div>
                <div>
                  <label className="block text-xs text-slate-500 mb-1">API Key</label>
                  <input type="password" className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700" placeholder="sk-..." />
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
