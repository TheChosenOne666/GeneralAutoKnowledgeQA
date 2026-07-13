package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.mapper.AuditLogMapper;
import com.xiongda.model.entity.AuditLog;
import com.xiongda.model.vo.AuditLogVO;
import com.xiongda.service.AuditLogService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 审计日志服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Service
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog> implements AuditLogService {

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLog(Long tenantId, Long userId, String userEmail, String action,
                          String resourceType, String resourceId, String detail,
                          String ipAddress, String userAgent) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setUserId(userId);
        log.setUserEmail(userEmail);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDetail(detail);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        this.save(log);
    }

    @Override
    public Page<AuditLogVO> listLogsByTenant(Long tenantId, String action, String userEmail,
                                             Date startTime, Date endTime, long current, long pageSize) {
        QueryWrapper<AuditLog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId);
        applyFilters(queryWrapper, action, userEmail, startTime, endTime);
        return toVOPage(this.page(new Page<>(current, pageSize), queryWrapper));
    }

    @Override
    public Page<AuditLogVO> listAllLogs(String action, String userEmail,
                                       Date startTime, Date endTime, long current, long pageSize) {
        QueryWrapper<AuditLog> queryWrapper = new QueryWrapper<>();
        applyFilters(queryWrapper, action, userEmail, startTime, endTime);
        return toVOPage(this.page(new Page<>(current, pageSize), queryWrapper));
    }

    /**
     * 统一追加筛选条件（操作类型 / 操作人 / 时间范围）。
     */
    private void applyFilters(QueryWrapper<AuditLog> queryWrapper, String action, String userEmail,
                              Date startTime, Date endTime) {
        if (StringUtils.isNotBlank(action)) {
            queryWrapper.eq("action", action);
        }
        if (StringUtils.isNotBlank(userEmail)) {
            queryWrapper.eq("user_email", userEmail);
        }
        if (startTime != null) {
            queryWrapper.ge("create_time", startTime);
        }
        if (endTime != null) {
            queryWrapper.le("create_time", endTime);
        }
        queryWrapper.orderByDesc("create_time");
    }

    private Page<AuditLogVO> toVOPage(Page<AuditLog> page) {
        Page<AuditLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    private AuditLogVO toVO(AuditLog log) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(log.getId());
        vo.setUserEmail(log.getUserEmail());
        vo.setAction(log.getAction());
        vo.setResourceType(log.getResourceType());
        vo.setResourceId(log.getResourceId());
        vo.setDetail(log.getDetail());
        vo.setIpAddress(log.getIpAddress());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
