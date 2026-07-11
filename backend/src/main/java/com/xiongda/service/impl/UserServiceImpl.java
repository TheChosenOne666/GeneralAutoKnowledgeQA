package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.common.ErrorCode;
import com.xiongda.constant.CommonConstant;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.BusinessException;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.mapper.TenantMapper;
import com.xiongda.mapper.UserMapper;
import com.xiongda.model.dto.user.UserInviteRequest;
import com.xiongda.model.dto.user.UserLoginRequest;
import com.xiongda.model.dto.user.UserRegisterRequest;
import com.xiongda.model.dto.user.UserUpdateRequest;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.LoginUserVO;
import com.xiongda.model.vo.UserVO;
import com.xiongda.service.UserService;
import com.xiongda.utils.JwtUtil;
import com.xiongda.utils.NetUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 用户服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    private TenantMapper tenantMapper;

    @Resource
    private JwtUtil jwtUtil;

    /**
     * 盐值（混淆密码）
     */
    private static final String SALT = "xiongda";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long userRegister(UserRegisterRequest request) {
        String email = request.getEmail();
        String userPassword = request.getUserPassword();
        String name = request.getName();

        // 校验邮箱是否已存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        Long count = this.baseMapper.selectCount(queryWrapper);
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "该邮箱已注册");

        // 为新用户创建租户
        Tenant tenant = new Tenant();
        tenant.setName(name + "的租户");
        tenant.setSlug("tenant-" + UUID.randomUUID().toString().substring(0, 8));
        tenant.setStatus("active");
        tenant.setMaxMembers(50);
        tenant.setMaxDocuments(1000);
        tenantMapper.insert(tenant);

        // 创建用户（首个用户默认租户管理员）
        User user = new User();
        user.setTenantId(tenant.getId());
        user.setName(name);
        user.setEmail(email);
        String encryptPassword = encryptPassword(userPassword);
        user.setUserPassword(encryptPassword);
        user.setRole(UserConstant.TENANT_ADMIN_ROLE);
        user.setIsActive(1);
        this.save(user);

        return user.getId();
    }

    @Override
    public LoginUserVO userLogin(UserLoginRequest request, HttpServletRequest httpServletRequest) {
        String email = request.getEmail();
        String userPassword = request.getUserPassword();

        // 查询用户
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        User user = this.baseMapper.selectOne(queryWrapper);
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "邮箱或密码错误");

        // 校验密码
        String encryptPassword = encryptPassword(userPassword);
        ThrowUtils.throwIf(!encryptPassword.equals(user.getUserPassword()),
                ErrorCode.PARAMS_ERROR, "邮箱或密码错误");

        // 校验状态
        ThrowUtils.throwIf(user.getIsActive() == 0, ErrorCode.FORBIDDEN_ERROR, "用户已被停用");

        // 生成 JWT
        String token = jwtUtil.generateToken(user.getId(), user.getTenantId(), user.getRole());

        return getLoginUserVO(user, token);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        String authorization = request.getHeader(CommonConstant.AUTHORIZATION_HEADER);
        ThrowUtils.throwIf(StringUtils.isBlank(authorization), ErrorCode.NOT_LOGIN_ERROR);

        String token = authorization.substring(CommonConstant.TOKEN_PREFIX.length());
        // 解析 token
        com.xiongda.model.entity.User loginUser = null;
        try {
            io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
            Long userId = jwtUtil.getUserId(claims);
            loginUser = this.baseMapper.selectById(userId);
            ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
            ThrowUtils.throwIf(loginUser.getIsActive() == 0, ErrorCode.FORBIDDEN_ERROR, "用户已被停用");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return loginUser;
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        return getLoginUserVO(user, null);
    }

    private LoginUserVO getLoginUserVO(User user, String token) {
        LoginUserVO loginUserVO = new LoginUserVO();
        loginUserVO.setId(user.getId());
        loginUserVO.setName(user.getName());
        loginUserVO.setEmail(user.getEmail());
        loginUserVO.setRole(user.getRole());
        loginUserVO.setTenantId(user.getTenantId());
        loginUserVO.setAvatarUrl(user.getAvatarUrl());
        if (token != null) {
            loginUserVO.setToken(token);
        }
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        userVO.setId(user.getId());
        userVO.setName(user.getName());
        userVO.setEmail(user.getEmail());
        userVO.setRole(user.getRole());
        userVO.setTenantId(user.getTenantId());
        userVO.setAvatarUrl(user.getAvatarUrl());
        userVO.setIsActive(user.getIsActive());
        userVO.setLastActiveAt(user.getLastActiveAt());
        userVO.setCreateTime(user.getCreateTime());
        return userVO;
    }

    @Override
    public Page<UserVO> listUsersByTenant(Long tenantId, QueryWrapper<User> queryWrapper, long current, long pageSize) {
        queryWrapper.eq("tenant_id", tenantId);
        Page<User> userPage = this.page(new Page<>(current, pageSize), queryWrapper);
        Page<UserVO> userVOPage = new Page<>(current, pageSize, userPage.getTotal());
        java.util.List<UserVO> userVOList = userPage.getRecords().stream()
                .map(this::getUserVO)
                .toList();
        userVOPage.setRecords(userVOList);
        return userVOPage;
    }

    @Override
    public boolean updateUser(Long tenantId, UserUpdateRequest request) {
        User user = this.baseMapper.selectById(request.getId());
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");
        ThrowUtils.throwIf(!tenantId.equals(user.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权操作其他租户成员");

        if (StringUtils.isNotBlank(request.getRole())) {
            user.setRole(request.getRole());
        }
        if (request.getIsActive() != null) {
            user.setIsActive(request.getIsActive());
        }
        return this.updateById(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long inviteMember(Long tenantId, UserInviteRequest request) {
        // 校验邮箱
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", request.getEmail());
        Long count = this.baseMapper.selectCount(queryWrapper);
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "该邮箱已注册");

        User user = new User();
        user.setTenantId(tenantId);
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setUserPassword(encryptPassword("123456")); // TODO: 邀请流程设置密码
        user.setRole(StringUtils.isNotBlank(request.getRole()) ? request.getRole() : UserConstant.DEFAULT_ROLE);
        user.setIsActive(1);
        this.save(user);

        return user.getId();
    }

    @Override
    public String getUserRole(Long userId) {
        User user = this.baseMapper.selectById(userId);
        return user != null ? user.getRole() : null;
    }

    /**
     * 密码加密（MD5 + 盐值）。
     */
    private String encryptPassword(String userPassword) {
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes(StandardCharsets.UTF_8));
    }
}
