package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.annotation.AuditLog;
import com.xiongda.common.ErrorCode;
import com.xiongda.constant.CommonConstant;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.BusinessException;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.mapper.TenantInvitationMapper;
import com.xiongda.mapper.TenantMapper;
import com.xiongda.mapper.UserMapper;
import com.xiongda.model.dto.user.UserAcceptInviteRequest;
import com.xiongda.model.dto.user.UserInviteRequest;
import com.xiongda.model.dto.user.UserLoginRequest;
import com.xiongda.model.dto.user.UserRegisterRequest;
import com.xiongda.model.dto.user.UserRemoveRequest;
import com.xiongda.model.dto.user.UserUpdateRequest;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.entity.TenantInvitation;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.InviteInfoVO;
import com.xiongda.model.vo.InviteResultVO;
import com.xiongda.model.vo.LoginUserVO;
import com.xiongda.model.vo.UserVO;
import com.xiongda.service.UserService;
import com.xiongda.utils.JwtUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
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
    private TenantInvitationMapper tenantInvitationMapper;

    @Resource
    private JwtUtil jwtUtil;

    @Value("${app.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    /**
     * 盐值（混淆密码）
     */
    private static final String SALT = "xiongda";

    /**
     * 邀请链接有效期：7 天。
     */
    private static final long INVITE_EXPIRE_MS = 7L * 24 * 60 * 60 * 1000;

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
    @AuditLog(action = "login", resourceType = "user")
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
        String authorization = request.getHeader(CommonConstant.AUTHORIZATION_HEADER);
        ThrowUtils.throwIf(StringUtils.isBlank(authorization), ErrorCode.NOT_LOGIN_ERROR);

        String token = authorization.substring(CommonConstant.TOKEN_PREFIX.length());
        // 解析 token（仅 JWT 解析异常视为未登录）
        com.xiongda.model.entity.User loginUser;
        try {
            io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
            Long userId = jwtUtil.getUserId(claims);
            loginUser = this.baseMapper.selectById(userId);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(loginUser.getIsActive() == 0, ErrorCode.FORBIDDEN_ERROR, "用户已被停用");

        // 平台超管支持通过 X-Tenant-ID 请求头切换到指定租户，从而以该租户身份操作其资源
        // （对齐 业界的 TenantSelector：超管切进某租户后当作 admin）。
        // 仅 super_admin 采纳该头，普通用户忽略，防止越权切换租户。
        if (UserConstant.SUPER_ADMIN_ROLE.equals(loginUser.getRole())) {
            String targetTenant = request.getHeader(CommonConstant.TENANT_HEADER);
            if (StringUtils.isNotBlank(targetTenant)) {
                try {
                    loginUser.setTenantId(Long.parseLong(targetTenant.trim()));
                } catch (NumberFormatException e) {
                    log.warn("平台超管 X-Tenant-ID 头非法，已忽略: {}", targetTenant);
                }
            }
        }
        return loginUser;
    }

    @Override
    @AuditLog(action = "logout", resourceType = "user")
    public void userLogout(HttpServletRequest request) {
        // 校验登录态（JWT 无状态，无服务端状态需清理）；审计由切面记录
        getLoginUser(request);
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
    @AuditLog(action = "member_change", resourceType = "member")
    public boolean updateUser(Long tenantId, Long operatorId, UserUpdateRequest request) {
        User user = this.baseMapper.selectById(request.getId());
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");
        ThrowUtils.throwIf(!tenantId.equals(user.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权操作其他租户成员");
        ThrowUtils.throwIf(UserConstant.SUPER_ADMIN_ROLE.equals(user.getRole()), ErrorCode.NO_AUTH_ERROR, "不能修改平台超管");

        boolean roleChanged = false;
        if (StringUtils.isNotBlank(request.getRole())) {
            String newRole = request.getRole();
            boolean valid = UserConstant.DEFAULT_ROLE.equals(newRole) || UserConstant.TENANT_ADMIN_ROLE.equals(newRole);
            ThrowUtils.throwIf(!valid, ErrorCode.PARAMS_ERROR, "角色只能是普通成员或租户管理员");
            // 不能把目标设为平台超管
            ThrowUtils.throwIf(UserConstant.SUPER_ADMIN_ROLE.equals(newRole), ErrorCode.PARAMS_ERROR, "不能设置为平台超管");
            // 不能把最后一个租户管理员降级（last-admin 不变量）
            if (!newRole.equals(user.getRole()) && UserConstant.TENANT_ADMIN_ROLE.equals(user.getRole())) {
                long adminCount = countTenantAdmins(tenantId);
                ThrowUtils.throwIf(adminCount <= 1, ErrorCode.OPERATION_ERROR, "不能移除最后一个租户管理员");
            }
            user.setRole(newRole);
            roleChanged = true;
        }

        if (request.getIsActive() != null) {
            user.setIsActive(request.getIsActive());
        }

        // 不能修改自己（防自锁导致租户无可用管理员）
        if (operatorId.equals(user.getId())) {
            ThrowUtils.throwIf(roleChanged, ErrorCode.OPERATION_ERROR, "不能修改自己的角色");
            ThrowUtils.throwIf(request.getIsActive() != null && request.getIsActive() == 0,
                    ErrorCode.OPERATION_ERROR, "不能停用自己");
        }

        return this.updateById(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "member_change", resourceType = "member")
    public InviteResultVO createInvitation(Long tenantId, Long inviterId, UserInviteRequest request) {
        String role = StringUtils.isNotBlank(request.getRole()) ? request.getRole() : UserConstant.DEFAULT_ROLE;
        boolean valid = UserConstant.DEFAULT_ROLE.equals(role) || UserConstant.TENANT_ADMIN_ROLE.equals(role);
        ThrowUtils.throwIf(!valid, ErrorCode.PARAMS_ERROR, "邀请角色只能是普通成员或租户管理员");

        TenantInvitation invitation = new TenantInvitation();
        invitation.setTenantId(tenantId);
        invitation.setInviterId(inviterId);
        invitation.setInviteeName(request.getName());
        invitation.setInviteeEmail(request.getEmail());
        invitation.setRole(role);
        String token = generateToken();
        invitation.setToken(token);
        invitation.setStatus("pending");
        invitation.setAcceptedCount(0);
        invitation.setExpiresAt(new Date(System.currentTimeMillis() + INVITE_EXPIRE_MS));
        tenantInvitationMapper.insert(invitation);

        InviteResultVO vo = new InviteResultVO();
        vo.setToken(token);
        vo.setInviteUrl(frontendBaseUrl + "/register?token=" + token);
        vo.setRole(role);
        vo.setExpiresAt(invitation.getExpiresAt());
        return vo;
    }

    @Override
    public InviteInfoVO getInviteInfo(String token) {
        TenantInvitation invitation = findValidInvitation(token);

        InviteInfoVO vo = new InviteInfoVO();
        User inviter = this.baseMapper.selectById(invitation.getInviterId());
        vo.setInviterName(inviter != null && StringUtils.isNotBlank(inviter.getName()) ? inviter.getName() : "管理员");
        Tenant tenant = tenantMapper.selectById(invitation.getTenantId());
        vo.setTenantName(tenant != null ? tenant.getName() : "");
        vo.setRole(invitation.getRole());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "member_change", resourceType = "member")
    public LoginUserVO acceptInvitation(UserAcceptInviteRequest request) {
        TenantInvitation invitation = findValidInvitation(request.getToken());

        // 邮箱唯一性
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", request.getEmail());
        ThrowUtils.throwIf(this.baseMapper.selectCount(queryWrapper) > 0, ErrorCode.PARAMS_ERROR, "该邮箱已注册");

        User user = new User();
        user.setTenantId(invitation.getTenantId());
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setUserPassword(encryptPassword(request.getUserPassword()));
        user.setRole(invitation.getRole());
        user.setIsActive(1);

        // 租户成员数配额校验（对标业界成熟方案：达到上限即拒绝，<=0 视为不限）
        Tenant tenant = tenantMapper.selectById(invitation.getTenantId());
        if (tenant != null && tenant.getMaxMembers() != null && tenant.getMaxMembers() > 0) {
            QueryWrapper<User> cntQw = new QueryWrapper<>();
            cntQw.eq("tenant_id", invitation.getTenantId());
            long memberCount = this.baseMapper.selectCount(cntQw);
            ThrowUtils.throwIf(memberCount >= tenant.getMaxMembers(),
                    ErrorCode.OPERATION_ERROR, "租户成员数已达上限");
        }

        this.save(user);

        // share-link 可复用：仅累加已加入人数，链接保持 pending 可继续分享
        invitation.setAcceptedCount(invitation.getAcceptedCount() + 1);
        tenantInvitationMapper.updateById(invitation);

        String token = jwtUtil.generateToken(user.getId(), user.getTenantId(), user.getRole());
        return getLoginUserVO(user, token);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "member_change", resourceType = "member")
    public boolean removeMember(Long tenantId, Long operatorId, UserRemoveRequest request) {
        Long targetId = request.getId();
        ThrowUtils.throwIf(targetId == null, ErrorCode.PARAMS_ERROR, "成员 ID 不能为空");
        ThrowUtils.throwIf(targetId.equals(operatorId), ErrorCode.PARAMS_ERROR, "不能移除自己");

        User target = this.baseMapper.selectById(targetId);
        ThrowUtils.throwIf(target == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");
        ThrowUtils.throwIf(!tenantId.equals(target.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权操作其他租户成员");
        ThrowUtils.throwIf(UserConstant.SUPER_ADMIN_ROLE.equals(target.getRole()),
                ErrorCode.NO_AUTH_ERROR, "不能移除平台超管");

        // 不能移除最后一个租户管理员（last-admin 不变量）
        if (UserConstant.TENANT_ADMIN_ROLE.equals(target.getRole())) {
            long adminCount = countTenantAdmins(tenantId);
            ThrowUtils.throwIf(adminCount <= 1, ErrorCode.OPERATION_ERROR, "不能移除最后一个租户管理员");
        }

        // 逻辑删除（@TableLogic）
        return this.removeById(targetId);
    }

    @Override
    public String getUserRole(Long userId) {
        User user = this.baseMapper.selectById(userId);
        return user != null ? user.getRole() : null;
    }

    /**
     * 统计租户内未删除的租户管理员数量。
     */
    private long countTenantAdmins(Long tenantId) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId).eq("role", UserConstant.TENANT_ADMIN_ROLE);
        return this.baseMapper.selectCount(queryWrapper);
    }

    /**
     * 查找并校验邀请链接有效（存在、未撤销、未过期）。
     */
    private TenantInvitation findValidInvitation(String token) {
        ThrowUtils.throwIf(StringUtils.isBlank(token), ErrorCode.PARAMS_ERROR, "邀请令牌不能为空");
        QueryWrapper<TenantInvitation> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("token", token);
        TenantInvitation invitation = tenantInvitationMapper.selectOne(queryWrapper);
        ThrowUtils.throwIf(invitation == null, ErrorCode.PARAMS_ERROR, "邀请链接无效或不存在");
        ThrowUtils.throwIf("revoked".equals(invitation.getStatus()), ErrorCode.PARAMS_ERROR, "邀请链接已撤销");
        ThrowUtils.throwIf(invitation.getExpiresAt() != null && invitation.getExpiresAt().getTime() < System.currentTimeMillis(),
                ErrorCode.PARAMS_ERROR, "邀请链接已过期");
        return invitation;
    }

    /**
     * 生成安全的随机令牌（256-bit，base64url 无填充）。
     */
    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 密码加密（MD5 + 盐值）。
     */
    private String encryptPassword(String userPassword) {
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes(StandardCharsets.UTF_8));
    }
}
