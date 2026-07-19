package com.xiongda.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiongda.annotation.AuthCheck;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.PageRequest;
import com.xiongda.common.ResultUtils;
import com.xiongda.constant.UserConstant;
import com.xiongda.model.dto.tenant.TenantCreateRequest;
import com.xiongda.model.dto.tenant.TenantQuotaRequest;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.TenantVO;
import com.xiongda.service.TenantService;
import com.xiongda.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 租户管理控制器 — 仅平台超管可访问。
 *
 * <p>M6-1：新增检索配置接口（GET/PUT /api/tenant/retrieval-config），
 * 供租户管理员自定义 RRF 融合参数（k、向量/关键词权重、门槛等）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    @Resource
    private TenantService tenantService;

    @Resource
    private UserService userService;

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

    /**
     * M6-1：获取当前租户的检索配置（RRF 参数等）。
     * 返回 JSON 字符串，前端直接解析渲染。
     */
    @GetMapping("/retrieval-config")
    public BaseResponse<String> getRetrievalConfig(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(tenantService.getRetrievalConfig(loginUser.getTenantId()));
    }

    /**
     * M6-1：更新当前租户的检索配置。
     * 接收 JSON 字符串，校验后写入 tenant.retrieval_config。
     */
    @PutMapping("/retrieval-config")
    public BaseResponse<Boolean> updateRetrievalConfig(HttpServletRequest request, @RequestBody String configJson) {
        User loginUser = userService.getLoginUser(request);
        tenantService.updateRetrievalConfig(loginUser.getTenantId(), configJson);
        return ResultUtils.success(true);
    }
}
