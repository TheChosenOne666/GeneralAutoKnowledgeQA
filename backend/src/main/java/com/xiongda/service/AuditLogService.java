package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.AuditLog;
import com.xiongda.model.vo.AuditLogVO;

import java.util.List;

/**
 * 审计日志服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface AuditLogService extends IService<AuditLog> {

    /**
     * 记录审计日志。
     */
    void recordLog(Long tenantId, Long userId, String userEmail, String action,
                   String resourceType, String resourceId, String detail, String ipAddress);

    /**
     * 查询审计日志（租户级）。
     */
    List<AuditLogVO> listLogsByTenant(Long tenantId, String action, long current, long pageSize);

    /**
     * 查询全局审计日志（平台超管）。
     */
    List<AuditLogVO> listAllLogs(String action, long current, long pageSize);
}
