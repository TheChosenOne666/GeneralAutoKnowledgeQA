/** 成员管理页骨架。*/

export default function MembersPage() {
  return (
    <div className="flex flex-col h-full">
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center justify-between px-6">
        <h3 className="text-base font-bold text-slate-800">成员管理</h3>
        <button className="px-5 py-2 bg-gradient-to-r from-brand-600 to-brand-500 text-white text-sm font-semibold rounded-lg hover:shadow-lg transition">
          邀请成员
        </button>
      </div>
      <div className="flex-1 overflow-auto p-6">
        <div className="bg-white rounded-2xl border border-emerald-100 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-emerald-50/50 text-slate-500">
              <tr>
                <th className="text-left px-4 py-3 font-medium">成员</th>
                <th className="text-left px-4 py-3 font-medium">邮箱</th>
                <th className="text-left px-4 py-3 font-medium">角色</th>
                <th className="text-left px-4 py-3 font-medium">状态</th>
                <th className="text-left px-4 py-3 font-medium">加入时间</th>
                <th className="text-left px-4 py-3 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr className="border-t border-emerald-50">
                <td className="px-4 py-3">
                  <div className="flex items-center gap-2">
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-brand-500 to-teal-400 flex items-center justify-center text-white text-xs font-bold">张</div>
                    <span className="text-slate-700 font-medium">张三</span>
                  </div>
                </td>
                <td className="px-4 py-3 text-slate-500">zhangsan@company.com</td>
                <td className="px-4 py-3"><span className="px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-700 text-xs font-medium">管理员</span></td>
                <td className="px-4 py-3"><span className="px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-600 text-xs">活跃</span></td>
                <td className="px-4 py-3 text-slate-400">2026-07-01</td>
                <td className="px-4 py-3 text-slate-400">—</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
