/**
 * M6-1：检索配置面板（RRF 参数等），嵌入 AIConfigPage 底部。
 * 配置存储在 tenant.retrieval_config（JSONB），租户级。
 */

import { useEffect, useState } from 'react'
import { tenantApi } from '@/api/tenant'
import type { RetrievalConfig } from '@/types'
import { useToast } from '@/components/Toast'
import { useAuth } from '@/hooks/useAuth'

const DEFAULT_CONFIG: RetrievalConfig = {
  rrf_k: 60,
  rrf_vector_weight: 0.7,
  rrf_keyword_weight: 0.3,
  vector_min_relevance: 0.3,
  bm25_min_relevance: 1.0,
  rerank_min_relevance: 0.4,
  relative_ratio: 0.8,
  max_chunks_per_doc: 5,
}

const FIELDS: { key: keyof RetrievalConfig; label: string; desc: string; step?: string; min?: number; max?: number }[] = [
  { key: 'rrf_k', label: 'RRF K 值', desc: 'RRF 融合公式中的平滑常数，通常 30~120', step: '1', min: 1, max: 500 },
  { key: 'rrf_vector_weight', label: '向量权重', desc: '向量检索在 RRF 融合中的权重（0~1）', step: '0.05', min: 0, max: 1 },
  { key: 'rrf_keyword_weight', label: '关键词权重', desc: 'BM25 关键词检索在 RRF 融合中的权重（0~1）', step: '0.05', min: 0, max: 1 },
  { key: 'vector_min_relevance', label: '向量最低相关性', desc: '向量相似度低于此值的块直接丢弃', step: '0.01', min: 0, max: 1 },
  { key: 'bm25_min_relevance', label: 'BM25 最低相关性', desc: 'BM25 分数低于此值的块直接丢弃', step: '0.5', min: 0, max: 100 },
  { key: 'rerank_min_relevance', label: 'Rerank 最低相关性', desc: 'Rerank 后低于此值的块直接剔除', step: '0.01', min: 0, max: 1 },
  { key: 'relative_ratio', label: '相对相关性比值', desc: '与最优分差距超过此比值的块被过滤', step: '0.05', min: 0, max: 1 },
  { key: 'max_chunks_per_doc', label: '单文档最大块数', desc: '单篇文档最多返回的块数上限', step: '1', min: 1, max: 50 },
]

export default function RetrievalConfigPanel() {
  const { user } = useAuth()
  const { success } = useToast()
  const [config, setConfig] = useState<RetrievalConfig>(DEFAULT_CONFIG)
  const [original, setOriginal] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; msg: string } | null>(null)

  // 仅租户管理员可操作
  const canEdit = user?.role === 'tenant_admin' || user?.role === 'super_admin'

  useEffect(() => {
    tenantApi.getRetrievalConfig()
      .then((cfg) => {
        setConfig(cfg)
        setOriginal(JSON.stringify(cfg))
      })
      .catch(() => setFeedback({ type: 'error', msg: '加载检索配置失败' }))
      .finally(() => setLoading(false))
  }, [])

  const handleChange = (key: keyof RetrievalConfig, value: string) => {
    const num = parseFloat(value)
    setConfig((prev) => ({ ...prev, [key]: isNaN(num) ? 0 : num }))
  }

  const handleSave = () => {
    setSaving(true)
    setFeedback(null)
    tenantApi.updateRetrievalConfig(config)
      .then(() => {
        setOriginal(JSON.stringify(config))
        setFeedback({ type: 'success', msg: '检索配置已保存' })
        success('检索配置已保存')
      })
      .catch(() => setFeedback({ type: 'error', msg: '保存失败，请重试' }))
      .finally(() => setSaving(false))
  }

  const handleReset = () => {
    setConfig(DEFAULT_CONFIG)
    setFeedback(null)
  }

  const hasChanges = JSON.stringify(config) !== original

  if (loading) {
    return (
      <div className="bg-white border border-emerald-100 rounded-2xl p-6 mt-6">
        <div className="animate-pulse text-sm text-slate-400">加载检索配置中…</div>
      </div>
    )
  }

  return (
    <div className="bg-white border border-emerald-100 rounded-2xl p-6 mt-6">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h4 className="text-sm font-bold text-slate-800">检索配置</h4>
          <p className="text-xs text-slate-400 mt-0.5">租户级 RRF 融合参数与相关性门槛，影响问答检索质量</p>
        </div>
        <div className="flex items-center gap-3">
          {feedback && (
            <span className={`text-xs font-medium ${feedback.type === 'success' ? 'text-emerald-600' : 'text-red-500'}`}>
              {feedback.msg}
            </span>
          )}
          <button
            onClick={handleReset}
            className="px-3 py-1.5 text-xs font-medium text-slate-500 rounded-lg border border-slate-200 hover:bg-slate-50 transition"
          >
            恢复默认
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !hasChanges || !canEdit}
            className="px-4 py-1.5 text-xs font-semibold text-white rounded-lg bg-gradient-to-r from-brand-600 to-brand-500 shadow-[0_4px_16px_rgba(16,185,129,0.3)] hover:opacity-90 transition disabled:opacity-50"
          >
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>

      {!canEdit && (
        <p className="text-xs text-amber-600 mb-3">仅租户管理员可修改检索配置</p>
      )}

      <div className="grid grid-cols-2 gap-x-6 gap-y-4">
        {FIELDS.map((f) => (
          <div key={f.key}>
            <label className="block text-xs font-medium text-slate-600 mb-1">{f.label}</label>
            <input
              type="number"
              value={config[f.key]}
              step={f.step}
              min={f.min}
              max={f.max}
              onChange={(e) => handleChange(f.key, e.target.value)}
              disabled={!canEdit}
              className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700 focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none disabled:bg-slate-50 disabled:text-slate-400"
            />
            <p className="text-[11px] text-slate-400 mt-0.5">{f.desc}</p>
          </div>
        ))}
      </div>

      {hasChanges && (
        <p className="text-xs text-amber-600 mt-3">⚠ 配置已修改，记得保存</p>
      )}
    </div>
  )
}
