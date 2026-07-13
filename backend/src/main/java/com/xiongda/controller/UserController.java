package com.xiongda.controller;

import com.xiongda.annotation.AuthCheck;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.PageRequest;
import com.xiongda.common.ResultUtils;
import com.xiongda.constant.UserConstant;
import com.xiongda.model.dto.user.UserAcceptInviteRequest;
import com.xiongda.model.dto.user.UserInviteRequest;
import com.xiongda.model.dto.user.UserLoginRequest;
import com.xiongda.model.dto.user.UserRegisterRequest;
import com.xiongda.model.dto.user.UserRemoveRequest;
import com.xiongda.model.dto.user.UserUpdateRequest;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.InviteInfoVO;
import com.xiongda.model.vo.InviteResultVO;
import com.xiongda.model.vo.LoginUserVO;
import com.xiongda.model.vo.UserVO;
import com.xiongda.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器 — 登录、注册、成员管理、邀请。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户注册。
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@Valid @RequestBody UserRegisterRequest request) {
        Long result = userService.userRegister(request);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录。
     */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@Valid @RequestBody UserLoginRequest request,
                                                HttpServletRequest httpServletRequest) {
        LoginUserVO loginUserVO = userService.userLogin(request, httpServletRequest);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户。
     */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户登出（JWT 无状态；仅记录审计日志）。
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        userService.userLogout(request);
        return ResultUtils.success(true);
    }

    /**
     * 获取成员列表（仅租户管理员）。
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = {UserConstant.TENANT_ADMIN_ROLE})
    public BaseResponse<java.util.List<UserVO>> listMembers(PageRequest pageRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO> page = userService.listUsersByTenant(
                loginUser.getTenantId(), queryWrapper, pageRequest.getCurrent(), pageRequest.getPageSize());
        return ResultUtils.success(page.getRecords());
    }

    /**
     * 更新成员（仅租户管理员）。
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = {UserConstant.TENANT_ADMIN_ROLE})
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest request, HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        boolean result = userService.updateUser(loginUser.getTenantId(), loginUser.getId(), request);
        return ResultUtils.success(result);
    }

    /**
     * 生成邀请链接（仅租户管理员）。
     */
    @PostMapping("/invite")
    @AuthCheck(mustRole = {UserConstant.TENANT_ADMIN_ROLE})
    public BaseResponse<InviteResultVO> inviteMember(@Valid @RequestBody UserInviteRequest request, HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        InviteResultVO result = userService.createInvitation(loginUser.getTenantId(), loginUser.getId(), request);
        return ResultUtils.success(result);
    }

    /**
     * 获取邀请链接详情（公开，供注册页预填/展示）。
     */
    @GetMapping("/invite/info")
    public BaseResponse<InviteInfoVO> inviteInfo(@RequestParam String token) {
        InviteInfoVO result = userService.getInviteInfo(token);
        return ResultUtils.success(result);
    }

    /**
     * 通过邀请链接注册并自动登录（公开）。
     */
    @PostMapping("/invite/accept")
    public BaseResponse<LoginUserVO> acceptInvite(@Valid @RequestBody UserAcceptInviteRequest request) {
        LoginUserVO result = userService.acceptInvitation(request);
        return ResultUtils.success(result);
    }

    /**
     * 移除成员（软删除，仅租户管理员）。
     */
    @PostMapping("/remove")
    @AuthCheck(mustRole = {UserConstant.TENANT_ADMIN_ROLE})
    public BaseResponse<Boolean> removeMember(@RequestBody UserRemoveRequest request, HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        boolean result = userService.removeMember(loginUser.getTenantId(), loginUser.getId(), request);
        return ResultUtils.success(result);
    }
}
