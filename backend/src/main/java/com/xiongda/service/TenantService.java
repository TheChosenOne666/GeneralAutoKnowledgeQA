package com.xiongda.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.dto.tenant.TenantCreateRequest;
import com.xiongda.model.dto.tenant.TenantQuotaRequest;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.vo.TenantVO;

/**
 * 租户服务接口 — 平台超管管理所有租户。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface TenantService extends IService<Tenant> {

    /**
     * 分页列出所有租户（含实时成员数 / 文档数）。
     */
    Page<TenantVO> listTenants(long current, long pageSize);

    /**
     * 创建租户，并把指定已注册邮箱的用户设为首个租户管理员。
     */
    TenantVO createTenant(TenantCreateRequest request);

    /**
     * 启用 / 停用租户（active / suspended）。
     */
    TenantVO setStatus(Long tenantId, String status);

    /**
     * 设置租户配额（成员数 / 文档数上限，<=0 视为不限）。
     */
    TenantVO setQuota(Long tenantId, TenantQuotaRequest request);

    /**
     * M6-1：获取指定租户的检索配置 JSON（RRF 参数等）。
     */
    String getRetrievalConfig(Long tenantId);

    /**
     * M6-1：更新指定租户的检索配置 JSON。
     */
    void updateRetrievalConfig(Long tenantId, String configJson);
}
