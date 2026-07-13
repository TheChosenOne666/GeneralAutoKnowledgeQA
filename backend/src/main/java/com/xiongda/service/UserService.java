package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
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
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册。
     */
    Long userRegister(UserRegisterRequest request);

    /**
     * 用户登录。
     */
    LoginUserVO userLogin(UserLoginRequest request, HttpServletRequest httpServletRequest);

    /**
     * 获取当前登录用户。
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 用户登出（JWT 无状态，无服务端状态需清理；仅记录审计日志）。
     */
    void userLogout(HttpServletRequest request);

    /**
     * 获取登录用户脱敏信息。
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 获取用户脱敏信息。
     */
    UserVO getUserVO(User user);

    /**
     * 分页查询用户（租户内）。
     */
    Page<UserVO> listUsersByTenant(Long tenantId, QueryWrapper<User> queryWrapper, long current, long pageSize);

    /**
     * 更新成员角色/状态（含防护：不能改 super_admin、不能降级最后一个管理员、不能自锁）。
     */
    boolean updateUser(Long tenantId, Long operatorId, UserUpdateRequest request);

    /**
     * 生成邀请链接（share-link 模式，可多人复用）。
     */
    InviteResultVO createInvitation(Long tenantId, Long inviterId, UserInviteRequest request);

    /**
     * 获取邀请链接详情（公开）。
     */
    InviteInfoVO getInviteInfo(String token);

    /**
     * 通过邀请链接注册并自动登录（公开）。
     */
    LoginUserVO acceptInvitation(UserAcceptInviteRequest request);

    /**
     * 软删除成员（含防护：不能移除自己、不能移除最后一个管理员、不能移除平台超管）。
     */
    boolean removeMember(Long tenantId, Long operatorId, UserRemoveRequest request);

    /**
     * 根据租户ID获取用户角色。
     */
    String getUserRole(Long userId);
}
