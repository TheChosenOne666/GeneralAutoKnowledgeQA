package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.dto.user.UserInviteRequest;
import com.xiongda.model.dto.user.UserLoginRequest;
import com.xiongda.model.dto.user.UserRegisterRequest;
import com.xiongda.model.dto.user.UserUpdateRequest;
import com.xiongda.model.entity.User;
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
     * 更新成员角色/状态。
     */
    boolean updateUser(Long tenantId, UserUpdateRequest request);

    /**
     * 邀请成员。
     */
    Long inviteMember(Long tenantId, UserInviteRequest request);

    /**
     * 根据租户ID获取用户角色。
     */
    String getUserRole(Long userId);
}
