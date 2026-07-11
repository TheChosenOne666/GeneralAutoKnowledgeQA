/** 成员管理页 — 按 05-members.html 设计稿重写。*/

const MEMBERS = [
  { name: '张三', email: 'zhangsan@lingou.com', role: 'admin', joinedAt: '2026-06-01', gradient: 'from-brand-500 to-brand-600' },
  { name: '李四', email: 'lisi@lingou.com', role: 'member', joinedAt: '2026-06-15', gradient: 'from-emerald-400 to-teal-500' },
  { name: '王五', email: 'wangwu@lingou.com', role: 'member', joinedAt: '2026-07-01', gradient: 'from-amber-400 to-amber-500' },
  { name: '赵六', email: 'zhaoliu@lingou.com', role: 'member', joinedAt: '2026-07-08', gradient: 'from-rose-400 to-rose-500' },
]

const PERMISSIONS = [
  { label: '提问 / 查看回答', member: true, admin: true, super: true },
  { label: '上传 / 管理文档', member: false, admin: true, super: true },
  { label: '管理成员 / 角色分配', member: false, admin: true, super: true },
  { label: '配置 AI 模型', member: false, admin: true, super: true },
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

export default function MembersPage() {
  return (
    <div className="flex flex-col h-full">
      {/* 顶部栏 */}
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6 flex-shrink-0">
        <h3 className="text-base font-bold text-slate-800">成员管理</h3>
        <button className="px-4 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg flex items-center gap-2 shadow-[0_8px_32px_rgba(16,185,129,0.3)] hover:opacity-90 transition">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          邀请成员
        </button>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {/* 成员列表 */}
        <div className="bg-white rounded-xl border border-emerald-100 overflow-hidden mb-6">
          <table className="w-full">
            <thead className="bg-emerald-50/50 border-b border-emerald-100">
              <tr>
                {['成员', '邮箱', '角色', '加入时间', '操作'].map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-50">
              {MEMBERS.map((m) => (
                <tr key={m.email} className="hover:bg-emerald-50/40 transition">
                  <td className="px-4 py-3.5">
                    <div className="flex items-center gap-3">
                      <div className={`w-9 h-9 rounded-full bg-gradient-to-br ${m.gradient} flex items-center justify-center text-white text-sm font-bold`}>{m.name[0]}</div>
                      <span className="text-sm text-slate-800 font-medium">{m.name}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3.5 text-sm text-slate-600">{m.email}</td>
                  <td className="px-4 py-3.5">
                    {m.role === 'admin' ? (
                      <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700">租户管理员</span>
                    ) : (
                      <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-blue-50 text-blue-600">普通成员</span>
                    )}
                  </td>
                  <td className="px-4 py-3.5 text-sm text-slate-500">{m.joinedAt}</td>
                  <td className="px-4 py-3.5">
                    <div className="flex items-center gap-1">
                      {m.role !== 'admin' && (
                        <button className="px-2.5 py-1 rounded-md bg-emerald-50 text-brand-700 text-xs font-semibold hover:bg-emerald-100 transition">设为管理员</button>
                      )}
                      <button className="px-2.5 py-1 rounded-md bg-red-50 text-red-600 text-xs font-semibold hover:bg-red-100 transition">移除</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* 权限矩阵 */}
        <div className="bg-white rounded-xl border border-emerald-100 p-6">
          <h4 className="font-bold text-slate-800 text-base mb-4">角色权限矩阵</h4>
          <table className="w-full">
            <thead>
              <tr className="border-b border-emerald-100">
                {['权限', '普通成员', '租户管理员', '平台超管'].map((h, i) => (
                  <th key={h} className={`px-4 py-2.5 ${i === 0 ? 'text-left' : 'text-center'} text-xs font-semibold text-slate-500 uppercase bg-emerald-50/50`}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-50">
              {PERMISSIONS.map((p) => (
                <tr key={p.label}>
                  <td className="px-4 py-2.5 text-sm text-slate-700 font-medium">{p.label}</td>
                  <td className="px-4 py-2.5 text-center">{p.member ? <Check /> : <span className="text-slate-300">—</span>}</td>
                  <td className="px-4 py-2.5 text-center">{p.admin ? <Check /> : <span className="text-slate-300">—</span>}</td>
                  <td className="px-4 py-2.5 text-center">{p.super ? <Check /> : <span className="text-slate-300">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
