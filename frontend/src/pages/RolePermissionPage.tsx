/** 角色权限说明页 — 展示各角色的能力矩阵（数据来自 design.md §3，与后端一致）。*/

type RoleKey = 'member' | 'tenant_admin' | 'super_admin'

const ROLE_LABELS: Record<RoleKey, string> = {
  member: '普通成员',
  tenant_admin: '租户管理员',
  super_admin: '平台超管',
}

const PERMISSIONS: { label: string; note?: string; member: boolean; admin: boolean; super: boolean }[] = [
  { label: '提问 / 查看回答', member: true, admin: true, super: true },
  { label: '上传 / 管理文档', note: '个人知识库上传/管理；共享库仅管理员可写', member: true, admin: true, super: true },
  { label: '配置 AI 模型', note: '每位成员可独立配置自己的模型密钥', member: true, admin: true, super: true },
  { label: '管理成员 / 角色分配', member: false, admin: true, super: true },
  { label: '查看审计日志', member: false, admin: true, super: true },
  { label: '管理所有租户', member: false, admin: false, super: true },
]

function Check() {
  return (
    <svg className="w-5 h-5 inline text-emerald-500" fill="currentColor" viewBox="0 0 20 20">
      <path d="M16.704 4.153a.75.75 0 0 1 .143 1.052l-8 10.5a.75.75 0 0 1-1.127.075l-4.5-4.5a.75.75 0 0 1 1.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 0 1 1.05-.143Z" />
    </svg>
  )
}

function Cell({ on }: { on: boolean }) {
  return on ? <Check /> : <span className="text-slate-300">—</span>
}

export default function RolePermissionPage() {
  return (
    <div className="flex flex-col h-full">
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">角色权限</h3>
      </div>

      <div className="flex-1 overflow-auto p-6">
        <div className="bg-white rounded-xl border border-emerald-100 p-6">
          <h4 className="font-bold text-slate-800 text-base mb-1">角色能力矩阵</h4>
          <p className="text-sm text-slate-400 mb-4">各角色在平台中的能力范围</p>
          <table className="w-full">
            <thead>
              <tr className="border-b border-emerald-100">
                {['能力', '普通成员', '租户管理员', '平台超管'].map((h, i) => (
                  <th
                    key={h}
                    className={`px-4 py-2.5 ${i === 0 ? 'text-left' : 'text-center'} text-xs font-semibold text-slate-500 uppercase bg-emerald-50/50`}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-50">
              {PERMISSIONS.map((p) => (
                <tr key={p.label}>
                  <td className="px-4 py-2.5 align-top">
                    <div className="text-sm text-slate-700 font-medium">{p.label}</div>
                    {p.note && <div className="text-xs text-slate-400 mt-0.5">{p.note}</div>}
                  </td>
                  <td className="px-4 py-2.5 text-center"><Cell on={p[roleKey('member')]} /></td>
                  <td className="px-4 py-2.5 text-center"><Cell on={p[roleKey('tenant_admin')]} /></td>
                  <td className="px-4 py-2.5 text-center"><Cell on={p[roleKey('super_admin')]} /></td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="mt-5 pt-4 border-t border-emerald-50 text-xs text-slate-400 leading-relaxed">
            说明：普通成员默认可访问知识库并上传/管理<strong className="text-slate-500">自己的个人知识库</strong>；共享知识库的写入仅限租户管理员。
            每位成员均可独立配置自己的 AI 模型（密钥隔离）。「管理所有租户」为平台超管专属能力。
          </div>
        </div>

        <p className="mt-3 text-xs text-slate-300">角色：{ROLE_LABELS.member} / {ROLE_LABELS.tenant_admin} / {ROLE_LABELS.super_admin}</p>
      </div>
    </div>
  )
}

function roleKey(role: RoleKey): 'member' | 'admin' | 'super' {
  return role === 'member' ? 'member' : role === 'tenant_admin' ? 'admin' : 'super'
}
