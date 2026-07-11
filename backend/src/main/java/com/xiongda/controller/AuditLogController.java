package com.xiongda.controller;

import com.xiongda.annotation.AuthCheck;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.PageRequest;
import com.xiongda.common.ResultUtils;
import com.xiongda.constant.UserConstant;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.AuditLogVO;
import com.xiongda.service.AuditLogService;
import com.xiongda.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计日志控制器 — 租户管理员查本租户，平台超管查全局。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

    @Resource
    private AuditLogService auditLogService;

    @Resource
    private UserService userService;

    /**
     * 查询审计日志。
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = {UserConstant.TENANT_ADMIN_ROLE, UserConstant.SUPER_ADMIN_ROLE})
    public BaseResponse<List<AuditLogVO>> listLogs(
            PageRequest pageRequest,
            @RequestParam(required = false) String action,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);

        List<AuditLogVO> list;
        if (UserConstant.SUPER_ADMIN_ROLE.equals(loginUser.getRole())) {
            list = auditLogService.listAllLogs(action, pageRequest.getCurrent(), pageRequest.getPageSize());
        } else {
            list = auditLogService.listLogsByTenant(
                    loginUser.getTenantId(), action, pageRequest.getCurrent(), pageRequest.getPageSize());
        }
        return ResultUtils.success(list);
    }
}
