/** AI 模型配置页 — 按 04-ai-config.html 设计稿，接通后端读取/保存。 */

import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { aiConfigApi } from '@/api/aiConfig'
import { useAuth } from '@/hooks/useAuth'
import { useToast } from '@/components/Toast'
import type { AIConfig, AIConfigUpdateRequest } from '@/types'
import RetrievalConfigPanel from '@/components/RetrievalConfigPanel'

const LLM_PROVIDERS = ['火山方舟', 'OpenAI', 'DeepSeek', '阿里云百炼']
const EMBEDDING_PROVIDERS = ['火山方舟', 'OpenAI', 'BGE', '阿里云百炼']

/** 选择厂商后自动填充的默认 endpoint / 温度 / 维度（用户可手动改）。*/
const PROVIDER_DEFAULTS: Record<
  string,
  { llmBaseUrl?: string; llmTemperature?: string; llmMaxTokens?: string; embeddingBaseUrl?: string; embeddingDimension?: string }
> = {
  '火山方舟': { llmBaseUrl: 'https://ark.cn-beijing.volces.com/api/v3', llmTemperature: '0.7', llmMaxTokens: '4096', embeddingBaseUrl: 'https://ark.cn-beijing.volces.com/api/v3', embeddingDimension: '2560' },
  'OpenAI': { llmBaseUrl: 'https://api.openai.com/v1', llmTemperature: '0.7', llmMaxTokens: '4096', embeddingBaseUrl: 'https://api.openai.com/v1', embeddingDimension: '1536' },
  'DeepSeek': { llmBaseUrl: 'https://api.deepseek.com/v1', llmTemperature: '0.7', llmMaxTokens: '4096', embeddingBaseUrl: '', embeddingDimension: '' },
  'BGE': { llmBaseUrl: '', llmTemperature: '', llmMaxTokens: '', embeddingBaseUrl: 'https://api.siliconflow.cn/v1', embeddingDimension: '1024' },
  '阿里云百炼': { llmBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', llmTemperature: '0.7', llmMaxTokens: '4096', embeddingBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', embeddingDimension: '1024' },
}

/** 表单状态（含 API Key 明文，仅用户主动输入时提交；留空不覆盖已存密钥）。*/
interface FormState {
  llmProvider: string
  llmModel: string
  llmApiKey: string
  llmBaseUrl: string
  llmTemperature: string
  llmMaxTokens: string
  llmModels: string[]
  embeddingProvider: string
  embeddingModel: string
  embeddingApiKey: string
  embeddingBaseUrl: string
  embeddingDimension: string
}

const EMPTY_FORM: FormState = {
  llmProvider: '',
  llmModel: '',
  llmApiKey: '',
  llmBaseUrl: '',
  llmTemperature: '',
  llmMaxTokens: '',
  llmModels: [],
  embeddingProvider: '',
  embeddingModel: '',
  embeddingApiKey: '',
  embeddingBaseUrl: '',
  embeddingDimension: '',
}

/** 将后端配置（不含 API Key）填入表单，API Key 字段保持空。*/
function toForm(cfg: AIConfig | null): FormState {
  if (!cfg) return { ...EMPTY_FORM }
  return {
    llmProvider: cfg.llmProvider ?? '',
    llmModel: cfg.llmModel ?? '',
    llmApiKey: '',
    llmBaseUrl: cfg.llmBaseUrl ?? '',
    llmTemperature: cfg.llmTemperature != null ? String(cfg.llmTemperature) : '',
    llmMaxTokens: cfg.llmMaxTokens != null ? String(cfg.llmMaxTokens) : '',
    llmModels: cfg.llmModels ?? [],
    embeddingProvider: cfg.embeddingProvider ?? '',
    embeddingModel: cfg.embeddingModel ?? '',
    embeddingApiKey: '',
    embeddingBaseUrl: cfg.embeddingBaseUrl ?? '',
    embeddingDimension: cfg.embeddingDimension != null ? String(cfg.embeddingDimension) : '',
  }
}

/** 将表单转为更新请求：空 API Key 不提交（后端 isNotBlank 判断，避免覆盖已存密钥）。*/
function toRequest(f: FormState): AIConfigUpdateRequest {
  return {
    llmProvider: f.llmProvider || null,
    llmModel: f.llmModel || null,
    llmApiKey: f.llmApiKey || null,
    llmBaseUrl: f.llmBaseUrl || null,
    llmTemperature: f.llmTemperature ? Number(f.llmTemperature) : null,
    llmMaxTokens: f.llmMaxTokens ? Number(f.llmMaxTokens) : null,
    llmModels: f.llmModels,
    embeddingProvider: f.embeddingProvider || null,
    embeddingModel: f.embeddingModel || null,
    embeddingApiKey: f.embeddingApiKey || null,
    embeddingBaseUrl: f.embeddingBaseUrl || null,
    embeddingDimension: f.embeddingDimension ? Number(f.embeddingDimension) : null,
  }
}

const LLM_ICON =
  'M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09Z'
const EMBEDDING_ICON =
  'M3.75 3v11.25A2.25 2.25 0 0 0 6 16.5h2.25M3.75 3h-1.5m1.5 0h16.5m0 0h1.5m-1.5 0v11.25A2.25 2.25 0 0 1 18 16.5h-2.25m-7.5 0h7.5m-7.5 0-1 3m8.5-3 1 3m0 0 .5 1.5m-.5-1.5h-9.5m0 0-.5 1.5'

/** 卡片标题栏。*/
function CardHeader({ icon, title, reserved }: { icon: string; title: string; reserved?: boolean }) {
  return (
    <div className="flex items-center gap-2.5 mb-4">
      <div className="w-8 h-8 rounded-lg bg-emerald-50 flex items-center justify-center">
        <svg className="w-5 h-5 text-brand-600" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d={icon} />
        </svg>
      </div>
      <h4 className="text-sm font-bold text-slate-800">{title}</h4>
      {reserved && <span className="px-2 py-0.5 rounded-full bg-slate-100 text-slate-400 text-[10px] font-bold">预留</span>}
    </div>
  )
}

/** 文本/密码/数字输入项。*/
function Field({
  label,
  type = 'text',
  value,
  onChange,
  placeholder,
  autoComplete = 'off',
}: {
  label: string
  type?: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  /** 默认 off，阻止浏览器把登录保存的账号密码自动填入本页（M4 修复）。密码类字段传 new-password。*/
  autoComplete?: string
}) {
  return (
    <div>
      <label className="block text-xs text-slate-500 mb-1">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder ?? label}
        autoComplete={autoComplete}
        className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700"
      />
    </div>
  )
}

/** 提供商下拉（若当前值不在预设列表内也一并展示）。*/
function ProviderSelect({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: string
  options: string[]
  onChange: (v: string) => void
}) {
  const merged = value && !options.includes(value) ? [value, ...options] : options
  return (
    <div>
      <label className="block text-xs text-slate-500 mb-1">{label}</label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700 bg-white"
      >
        <option value="">请选择</option>
        {merged.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </div>
  )
}

export default function AIConfigPage() {
  const { user } = useAuth()
  const { success } = useToast()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isSuperAdmin = user?.role === 'super_admin'
  /** 配置作用域：超管可在「我的配置」与「平台默认配置」间切换。*/
  const [scope, setScope] = useState<'user' | 'platform'>('user')

  const [config, setConfig] = useState<AIConfig | null>(null)
  const [form, setForm] = useState<FormState>({ ...EMPTY_FORM })
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; msg: string } | null>(null)
  /** 向量维度内联错误：保存时未填写则在维度框下方提示（与普通字段一致的保存校验体验）。*/
  const [dimensionError, setDimensionError] = useState<string | null>(null)

  const load = (s: 'user' | 'platform') => {
    const fetcher = s === 'platform' ? aiConfigApi.getPlatformDefault() : aiConfigApi.getConfig()
    return fetcher
      .then((cfg) => {
        setConfig(cfg)
        setForm(toForm(cfg))
      })
      .catch(() => setFeedback({ type: 'error', msg: '加载配置失败，请稍后重试' }))
  }

  useEffect(() => {
    load(scope)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope])

  const set = (patch: Partial<FormState>) => setForm((prev) => ({ ...prev, ...patch }))

  /** 待添加的额外模型输入框。*/
  const [newModel, setNewModel] = useState('')

  /** 切换 LLM 厂商时自动填充其默认 endpoint / 温度 / 最大 Token（用户可手动改）。*/
  const applyLlmProviderDefaults = (v: string) => {
    const d = PROVIDER_DEFAULTS[v] ?? {}
    set({
      llmProvider: v,
      llmBaseUrl: d.llmBaseUrl ?? form.llmBaseUrl,
      llmTemperature: d.llmTemperature ?? form.llmTemperature,
      llmMaxTokens: d.llmMaxTokens ?? form.llmMaxTokens,
    })
  }

  /** 切换 Embedding 厂商时自动填充其默认 endpoint（用户可手动改；向量维度不自动填，与模型名称一致）。*/
  const applyEmbeddingProviderDefaults = (v: string) => {
    const d = PROVIDER_DEFAULTS[v] ?? {}
    set({
      embeddingProvider: v,
      embeddingBaseUrl: d.embeddingBaseUrl ?? form.embeddingBaseUrl,
    })
  }

  /** 将输入框中的模型名加入多模型列表（去重、去空）。*/
  const addModel = () => {
    const m = newModel.trim()
    if (!m || form.llmModels.includes(m)) {
      setNewModel('')
      return
    }
    set({ llmModels: [...form.llmModels, m] })
    setNewModel('')
  }

  /** 从多模型列表移除指定模型。*/
  const removeModel = (m: string) => set({ llmModels: form.llmModels.filter((x) => x !== m) })

  const handleSave = async () => {
    setSaving(true)
    setFeedback(null)
    setDimensionError(null)
    // 向量维度必填校验：配置了 Embedding（provider / model 任一非空）就必须填写正整数维度。
    // 未填写时在维度框下方内联提示（与模型名称等字段一致的保存校验体验），不依赖红色必填样式。
    if (
      (form.embeddingProvider || form.embeddingModel) &&
      !(Number(form.embeddingDimension) > 0)
    ) {
      setSaving(false)
      setDimensionError('请填写向量维度（正整数）')
      return
    }
    // 模型必填校验：只要配置了某模块（provider 非空），就必须填写模型名称，
    // 否则提示用户补全并阻止保存（与后端拦截一致，避免「删掉模型名却保存成功」）。
    if (form.llmProvider && !form.llmModel.trim()) {
      setSaving(false)
      setFeedback({ type: 'error', msg: '请填写 LLM 模型名称' })
      return
    }
    if (form.embeddingProvider && !form.embeddingModel.trim()) {
      setSaving(false)
      setFeedback({ type: 'error', msg: '请填写 Embedding 模型名称' })
      return
    }
    try {
      const updated =
        scope === 'platform'
          ? await aiConfigApi.updatePlatformDefault(toRequest(form))
          : await aiConfigApi.updateConfig(toRequest(form))
      setConfig(updated)
      setForm(toForm(updated))
      setDimensionError(null)
      success(scope === 'platform' ? '平台默认配置已保存' : '配置保存成功')
      // 普通用户保存成功后，顶部绿色提示并按来源跳回：
      // 从知识库页去配置 → 回知识库页；从对话页去配置 → 回对话页；
      // 直接在菜单进入（无 from）→ 默认回对话页。
      if (scope !== 'platform') {
        const backTo = searchParams.get('from') || '/chat'
        window.setTimeout(() => navigate(backTo), 1200)
      }
    } catch (err) {
      setFeedback({ type: 'error', msg: `保存失败：${err instanceof Error ? err.message : '请稍后重试'}` })
    } finally {
      setSaving(false)
    }
  }

  const llmActive = Boolean(config?.llmProvider && config?.llmModel)
  const embeddingActive = Boolean(config?.embeddingProvider && config?.embeddingModel)

  const statusItems = [
    { label: 'LLM', value: llmActive ? `${config?.llmProvider} · ${config?.llmModel}` : '未配置', active: llmActive },
    {
      label: 'Embedding',
      value: embeddingActive ? `${config?.embeddingProvider} · ${config?.embeddingModel}` : '未配置',
      active: embeddingActive,
    },
  ]

  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">AI 模型配置</h3>
        <div className="flex items-center gap-3">
          {feedback && (
            <span className={`text-sm font-medium ${feedback.type === 'success' ? 'text-emerald-600' : 'text-red-500'}`}>
              {feedback.msg}
            </span>
          )}
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-5 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition disabled:opacity-50"
          >
            {saving ? '保存中...' : '保存配置'}
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {/* 平台超管：切换「我的配置 / 平台默认配置」*/}
        {isSuperAdmin && (
          <div className="flex items-center gap-3 mb-5">
            <span className="text-sm text-slate-500">配置作用域：</span>
            <div className="inline-flex rounded-lg border border-emerald-200 overflow-hidden">
              {(['user', 'platform'] as const).map((s) => (
                <button
                  key={s}
                  onClick={() => setScope(s)}
                  className={`px-4 py-1.5 text-sm font-medium transition ${
                    scope === s
                      ? 'bg-brand-600 text-white'
                      : 'bg-white text-slate-500 hover:bg-emerald-50'
                  }`}
                >
                  {s === 'user' ? '我的配置' : '平台默认配置'}
                </button>
              ))}
            </div>
            {scope === 'platform' && (
              <span className="text-xs text-amber-600">平台默认配置将对所有租户生效（用户未单独配置时回退到此）</span>
            )}
          </div>
        )}

        {/* 状态概览 */}
        <div className="bg-gradient-to-r from-brand-50 to-teal-50 border border-brand-200 rounded-2xl p-5 mb-6 flex gap-8">
          {statusItems.map((s) => (
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
          {/* LLM */}
          <div className="bg-white border border-emerald-100 rounded-2xl p-6">
            <CardHeader icon={LLM_ICON} title="LLM 大语言模型" />
            <div className="space-y-3">
              <ProviderSelect label="提供商" value={form.llmProvider} options={LLM_PROVIDERS} onChange={applyLlmProviderDefaults} />
              <Field label="模型" value={form.llmModel} onChange={(v) => set({ llmModel: v })} placeholder="doubao-pro" />
              <p className="text-[11px] text-slate-400 mt-1">
                💡 如需在问答中上传图片（多模态），请填写支持 vision 的模型（如 gpt-4o、qwen-vl-max、glm-4v 等）
              </p>
              <div className="pt-1">
                <label className="block text-xs text-slate-500 mb-1">更多模型（可选，对话时可切换）</label>
                <div className="flex gap-2">
                  <input
                    value={newModel}
                    onChange={(e) => setNewModel(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        addModel()
                      }
                    }}
                    placeholder="如 deepseek-r1"
                    className="flex-1 px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-700"
                  />
                  <button
                    type="button"
                    onClick={addModel}
                    className="px-3 py-2 rounded-lg bg-brand-50 text-brand-700 text-sm font-medium border border-emerald-200 hover:bg-emerald-100 transition"
                  >
                    添加
                  </button>
                </div>
                {form.llmModels.length > 0 && (
                  <div className="flex flex-wrap gap-2 mt-2">
                    {form.llmModels.map((m) => (
                      <span
                        key={m}
                        className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-emerald-50 text-brand-700 text-xs font-medium border border-emerald-200"
                      >
                        {m}
                        <button
                          type="button"
                          onClick={() => removeModel(m)}
                          className="text-brand-500 hover:text-red-500"
                          aria-label={`移除 ${m}`}
                        >
                          ×
                        </button>
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <Field label="API Key" type="password" autoComplete="new-password" value={form.llmApiKey} onChange={(v) => set({ llmApiKey: v })} placeholder={config?.llmProvider ? '已配置（留空不修改）' : '请输入 API Key'} />
              <Field label="API Endpoint" value={form.llmBaseUrl} onChange={(v) => set({ llmBaseUrl: v })} placeholder="https://ark.cn-beijing.volces.com/api/v3" />
              <Field label="温度" type="number" value={form.llmTemperature} onChange={(v) => set({ llmTemperature: v })} placeholder="0.7" />
              <Field label="最大 Token" type="number" value={form.llmMaxTokens} onChange={(v) => set({ llmMaxTokens: v })} placeholder="4096" />
            </div>
          </div>

          {/* Embedding */}
          <div className="bg-white border border-emerald-100 rounded-2xl p-6">
            <CardHeader icon={EMBEDDING_ICON} title="Embedding 向量化模型" />
            <div className="space-y-3">
              <ProviderSelect label="提供商" value={form.embeddingProvider} options={EMBEDDING_PROVIDERS} onChange={applyEmbeddingProviderDefaults} />
              <Field label="模型" value={form.embeddingModel} onChange={(v) => set({ embeddingModel: v })} placeholder="doubao-embedding" />
              <Field label="API Key" type="password" autoComplete="new-password" value={form.embeddingApiKey} onChange={(v) => set({ embeddingApiKey: v })} placeholder={config?.embeddingProvider ? '已配置（留空不修改）' : '请输入 API Key'} />
              <Field label="API Endpoint" value={form.embeddingBaseUrl} onChange={(v) => set({ embeddingBaseUrl: v })} placeholder="https://ark.cn-beijing.volces.com/api/v3" />
              <Field label="向量维度" type="number" value={form.embeddingDimension} onChange={(v) => { set({ embeddingDimension: v }); setDimensionError(null) }} placeholder="1536" />
              {dimensionError && (
                <p className="mt-1 text-xs text-red-500">{dimensionError}</p>
              )}
            </div>
          </div>
        </div>

        {/* M6-1 检索配置面板 */}
        <RetrievalConfigPanel />
      </div>
    </div>
  )
}
