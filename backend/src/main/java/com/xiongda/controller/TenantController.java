package com.xiongda.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiongda.annotation.AuthCheck;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.PageRequest;
import com.xiongda.common.ResultUtils;
import com.xiongda.constant.UserConstant;
import com.xiongda.model.dto.tenant.TenantCreateRequest;
import com.xiongda.model.dto.tenant.TenantQuotaRequest;
import com.xiongda.model.vo.TenantVO;
import com.xiongda.service.TenantService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 租户管理控制器 — 仅平台超管可访问。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    @Resource
    private TenantService tenantService;

    /**
     * 分页列出所有租户。
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = UserConstant.SUPER_ADMIN_ROLE)
    public BaseResponse<Page<TenantVO>> listTenants(PageRequest pageRequest) {
        return ResultUtils.success(tenantService.listTenants(pageRequest.getCurrent(), pageRequest.getPageSize()));
    }

    /**
     * 创建租户并将指定邮箱用户设为首个租户管理员。
     */
    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.SUPER_ADMIN_ROLE)
    public BaseResponse<TenantVO> createTenant(@RequestBody TenantCreateRequest request) {
        return ResultUtils.success(tenantService.createTenant(request));
    }

    /**
     * 启用 / 停用租户。
     */
    @PostMapping("/{id}/status")
    @AuthCheck(mustRole = UserConstant.SUPER_ADMIN_ROLE)
    public BaseResponse<TenantVO> setStatus(@PathVariable Long id, @RequestParam String status) {
        return ResultUtils.success(tenantService.setStatus(id, status));
    }

    /**
     * 设置租户配额。
     */
    @PostMapping("/{id}/quota")
    @AuthCheck(mustRole = UserConstant.SUPER_ADMIN_ROLE)
    public BaseResponse<TenantVO> setQuota(@PathVariable Long id, @RequestBody TenantQuotaRequest request) {
        return ResultUtils.success(tenantService.setQuota(id, request));
    }
}
