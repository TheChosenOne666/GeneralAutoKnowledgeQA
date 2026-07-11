/** 审计日志页骨架。*/

export default function AuditLogPage() {
  return (
    <div className="flex flex-col h-full">
      <div className="h-16 bg-white border-b border-emerald-100 flex items-center px-6">
        <h3 className="text-base font-bold text-slate-800">审计日志</h3>
      </div>
      <div className="flex-1 overflow-auto p-6">
        {/* 筛选栏 */}
        <div className="flex items-center gap-3 mb-5">
          <select className="px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-600 bg-white">
            <option value="">全部操作</option>
            <option value="login">登录</option>
            <option value="doc_upload">文档上传</option>
            <option value="config_update">配置修改</option>
          </select>
          <input className="px-3 py-2 rounded-lg border border-emerald-200 text-sm text-slate-600 bg-white" placeholder="搜索关键词" />
        </div>

        {/* 日志表格 */}
        <div className="bg-white rounded-2xl border border-emerald-100 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-emerald-50/50 text-slate-500">
              <tr>
                <th className="text-left px-4 py-3 font-medium">时间</th>
                <th className="text-left px-4 py-3 font-medium">用户</th>
                <th className="text-left px-4 py-3 font-medium">操作类型</th>
                <th className="text-left px-4 py-3 font-medium">详情</th>
                <th className="text-left px-4 py-3 font-medium">IP</th>
              </tr>
            </thead>
            <tbody>
              <tr className="border-t border-emerald-50">
                <td className="px-4 py-3 text-slate-400">2026-07-11 13:00</td>
                <td className="px-4 py-3 text-slate-700">zhangsan@company.com</td>
                <td className="px-4 py-3"><span className="px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-600 text-xs">登录</span></td>
                <td className="px-4 py-3 text-slate-500">用户登录系统</td>
                <td className="px-4 py-3 text-slate-400">192.168.1.1</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
