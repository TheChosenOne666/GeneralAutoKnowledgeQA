package com.xiongda.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

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
     * 查询审计日志（支持操作类型 / 操作人 / 时间范围筛选，分页返回）。
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = {UserConstant.TENANT_ADMIN_ROLE, UserConstant.SUPER_ADMIN_ROLE})
    public BaseResponse<Page<AuditLogVO>> listLogs(
            PageRequest pageRequest,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);

        Page<AuditLogVO> page;
        if (UserConstant.SUPER_ADMIN_ROLE.equals(loginUser.getRole())) {
            page = auditLogService.listAllLogs(action, userEmail,
                    parseTime(startTime), parseEndTime(endTime),
                    pageRequest.getCurrent(), pageRequest.getPageSize());
        } else {
            page = auditLogService.listLogsByTenant(loginUser.getTenantId(), action, userEmail,
                    parseTime(startTime), parseEndTime(endTime),
                    pageRequest.getCurrent(), pageRequest.getPageSize());
        }
        return ResultUtils.success(page);
    }

    /**
     * 解析时间参数：支持 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss`。
     */
    private Date parseTime(String s) {
        if (StringUtils.isBlank(s)) {
            return null;
        }
        String fmt = s.length() > 10 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd";
        try {
            return new SimpleDateFormat(fmt).parse(s);
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * 结束时间：日期型补齐到当天 23:59:59，避免漏掉当日记录。
     */
    private Date parseEndTime(String s) {
        if (StringUtils.isBlank(s)) {
            return null;
        }
        return parseTime(s.length() <= 10 ? s + " 23:59:59" : s);
    }
}
