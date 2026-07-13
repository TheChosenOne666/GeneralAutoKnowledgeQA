package com.xiongda.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.AuditLog;
import com.xiongda.model.vo.AuditLogVO;

import java.util.Date;
import java.util.List;

/**
 * 审计日志服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface AuditLogService extends IService<AuditLog> {

    /**
     * 记录审计日志（独立事务，失败不影响主流程）。
     */
    void recordLog(Long tenantId, Long userId, String userEmail, String action,
                   String resourceType, String resourceId, String detail,
                   String ipAddress, String userAgent);

    /**
     * 查询审计日志（租户级，支持操作类型 / 操作人 / 时间范围筛选与分页）。
     */
    Page<AuditLogVO> listLogsByTenant(Long tenantId, String action, String userEmail,
                                       Date startTime, Date endTime, long current, long pageSize);

    /**
     * 查询全局审计日志（平台超管，支持筛选与分页）。
     */
    Page<AuditLogVO> listAllLogs(String action, String userEmail,
                                 Date startTime, Date endTime, long current, long pageSize);
}
