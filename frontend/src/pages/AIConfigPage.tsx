/** AI 模型配置页 — 按 04-ai-config.html 设计稿重写。*/

const STATUS_ITEMS = [
  { label: 'LLM', value: '火山方舟 · 豆包Pro', active: true },
  { label: 'Embedding', value: '火山方舟 · Doubao-embedding', active: true },
  { label: 'Rerank', value: '未配置', active: false },
]

const CONFIG_CARDS = [
  {
    title: 'LLM 大语言模型',
    icon: 'M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09Z',
    fields: [
      { label: '提供商', type: 'select', options: ['火山方舟', 'OpenAI', 'DeepSeek'], value: '火山方舟' },
      { label: '模型', type: 'input', value: 'doubao-pro' },
      { label: 'API Key', type: 'password', value: '' },
      { label: 'API Endpoint', type: 'input', value: 'https://ark.cn-beijing.volces.com/api/v3' },
      { label: '温度', type: 'input', value: '0.7' },
      { label: '最大 Token', type: 'input', value: '4096' },
    ],
  },
  {
    title: 'Embedding 向量化模型',
    icon: 'M3.75 3v11.25A2.25 2.25 0 0 0 6 16.5h2.25M3.75 3h-1.5m1.5 0h16.5m0 0h1.5m-1.5 0v11.25A2.25 2.25 0 0 1 18 16.5h-2.25m-7.5 0h7.5m-7.5 0-1 3m8.5-3 1 3m0 0 .5 1.5m-.5-1.5h-9.5m0 0-.5 1.5',
    fields: [
      { label: '提供商', type: 'select', options: ['火山方舟', 'OpenAI', 'BGE'], value: '火山方舟' },
      { label: '模型', type: 'input', value: 'doubao-embedding' },
      { label: 'API Key', type: 'password', value: '' },
      { label: 'API Endpoint', type: 'input', value: 'https://ark.cn-beijing.volces.com/api/v3' },
      { label: '向量维度', type: 'input', value: '1536' },
    ],
  },
  {
    title: 'Rerank 重排序模型',
    icon: 'M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 0 1 3 19.875v-6.75ZM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V8.625ZM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V4.125Z',
    fields: [
      { label: '提供商', type: 'select', options: ['火山方舟', 'Cohere', 'BGE'], value: '' },
      { label: '模型', type: 'input', value: '' },
      { label: 'API Key', type: 'password', value: '' },
    ],
    disabled: true,
  },
]

export default function AIConfigPage() {
  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">AI 模型配置</h3>
        <button className="px-5 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition">
          保存配置
        </button>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {/* 状态概览 */}
        <div className="bg-gradient-to-r from-brand-50 to-teal-50 border border-brand-200 rounded-2xl p-5 mb-6 flex gap-8">
          {STATUS_ITEMS.map((s) => (
            <div key={s.label} className="flex items-center gap-2.5">
              <div className={`w-2.5 h-2.5 rounded-full ${s.active ? 'bg-emerald-500' : 'bg-slate-300'}`} />
              <div>
                <div className="text-xs text-slate-500 font-medium">{s.label}</div>
                <div className={`text-sm font-bold ${s.active ? 'text-slate-800' : 'text-slate-400'}`}>{s.value}</div>
              </div>
            </div>
          ))}
        </div>

        {/* 配置卡片 */}
        <div className="grid grid-cols-2 gap-6">
          {CONFIG_CARDS.map((card) => (
            <div key={card.title} className={`bg-white border border-emerald-100 rounded-2xl p-6 ${card.disabled ? 'opacity-60' : ''}`}>
              <div className="flex items-center gap-2.5 mb-4">
                <div className="w-8 h-8 rounded-lg bg-emerald-50 flex items-center justify-center">
                  <svg className="w-5 h-5 text-brand-600" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d={card.icon} />
                  </svg>
                </div>
                <h4 className="text-sm font-bold text-slate-800">{card.title}</h4>
                {card.disabled && <span className="px-2 py-0.5 rounded-full bg-slate-100 text-slate-400 text-[10px] font-bold">预留</span>}
              </div>
              <div className="space-y-3">
                {card.fields.map((f) => (
                  <div key={f.label}>
                    <label className="block text-xs text-slate-500 mb-1">{f.label}</label>
                    {f.type === 'select' ? (
                      <select disabled={card.disabled} className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700 bg-white" defaultValue={f.value}>
                        <option value="">请选择</option>
                        {f.options?.map((o) => <option key={o} value={o}>{o}</option>)}
                      </select>
                    ) : (
                      <input type={f.type} disabled={card.disabled} className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700" defaultValue={f.value} placeholder={f.label} />
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
