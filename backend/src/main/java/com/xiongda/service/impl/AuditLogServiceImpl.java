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

import java.util.List;

/**
 * 审计日志服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Service
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog> implements AuditLogService {

    @Override
    public void recordLog(Long tenantId, Long userId, String userEmail, String action,
                          String resourceType, String resourceId, String detail, String ipAddress) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setUserId(userId);
        log.setUserEmail(userEmail);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDetail(detail);
        log.setIpAddress(ipAddress);
        this.save(log);
    }

    @Override
    public List<AuditLogVO> listLogsByTenant(Long tenantId, String action, long current, long pageSize) {
        QueryWrapper<AuditLog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId);
        if (StringUtils.isNotBlank(action)) {
            queryWrapper.eq("action", action);
        }
        queryWrapper.orderByDesc("create_time");
        Page<AuditLog> page = this.page(new Page<>(current, pageSize), queryWrapper);
        return page.getRecords().stream().map(this::toVO).toList();
    }

    @Override
    public List<AuditLogVO> listAllLogs(String action, long current, long pageSize) {
        QueryWrapper<AuditLog> queryWrapper = new QueryWrapper<>();
        if (StringUtils.isNotBlank(action)) {
            queryWrapper.eq("action", action);
        }
        queryWrapper.orderByDesc("create_time");
        Page<AuditLog> page = this.page(new Page<>(current, pageSize), queryWrapper);
        return page.getRecords().stream().map(this::toVO).toList();
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
