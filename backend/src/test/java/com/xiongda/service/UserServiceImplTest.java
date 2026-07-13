package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.common.ErrorCode;
import com.xiongda.constant.CommonConstant;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.BusinessException;
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
import com.xiongda.service.impl.UserServiceImpl;
import com.xiongda.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用户服务实现单元测试 — 覆盖注册、登录、获取登录用户、VO 转换核心逻辑。
 *
 * <p>使用 Mockito 纯单元测试，mock 掉 Mapper 和 JwtUtil，不依赖数据库。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    /**
     * 密码盐值，需与 {@link UserServiceImpl} 中的 SALT 保持一致。
     */
    private static final String SALT = "xiongda";

    @Mock
    private UserMapper userMapper;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private TenantInvitationMapper tenantInvitationMapper;

    @Mock
    private JwtUtil jwtUtil;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "tenantMapper", tenantMapper);
        ReflectionTestUtils.setField(userService, "tenantInvitationMapper", tenantInvitationMapper);
        ReflectionTestUtils.setField(userService, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(userService, "frontendBaseUrl", "http://localhost:5173");
    }

    // ==================== 注册 ====================

    @Test
    void userRegister_success() {
        UserRegisterRequest request = new UserRegisterRequest();
        request.setName("测试用户");
        request.setEmail("test@example.com");
        request.setUserPassword("123456");

        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(tenantMapper.insert(any(Tenant.class))).thenAnswer(inv -> {
            inv.getArgument(0, Tenant.class).setId(1L);
            return 1;
        });
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            inv.getArgument(0, User.class).setId(100L);
            return 1;
        });

        Long userId = userService.userRegister(request);
        assertEquals(100L, userId);

        // 验证租户创建
        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).insert(tenantCaptor.capture());
        assertEquals("active", tenantCaptor.getValue().getStatus());

        // 验证用户创建：角色为租户管理员、密码已加密
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("test@example.com", savedUser.getEmail());
        assertEquals(UserConstant.TENANT_ADMIN_ROLE, savedUser.getRole());
        assertEquals(1, savedUser.getIsActive());
        assertNotEquals("123456", savedUser.getUserPassword());
    }

    @Test
    void userRegister_emailExists() {
        UserRegisterRequest request = new UserRegisterRequest();
        request.setName("测试");
        request.setEmail("exist@example.com");
        request.setUserPassword("123456");

        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.userRegister(request));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    // ==================== 登录 ====================

    @Test
    void userLogin_success() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("test@example.com");
        request.setUserPassword("123456");

        User user = buildUser(100L, 1L, "test@example.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        user.setUserPassword(encrypt("123456"));

        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
        when(jwtUtil.generateToken(100L, 1L, UserConstant.TENANT_ADMIN_ROLE)).thenReturn("mock-token");

        LoginUserVO vo = userService.userLogin(request, mock(HttpServletRequest.class));

        assertEquals(100L, vo.getId());
        assertEquals("test@example.com", vo.getEmail());
        assertEquals(UserConstant.TENANT_ADMIN_ROLE, vo.getRole());
        assertEquals("mock-token", vo.getToken());
    }

    @Test
    void userLogin_userNotFound() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("none@example.com");
        request.setUserPassword("123456");

        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.userLogin(request, mock(HttpServletRequest.class)));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void userLogin_wrongPassword() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("test@example.com");
        request.setUserPassword("wrongpwd");

        User user = buildUser(100L, 1L, "test@example.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        user.setUserPassword(encrypt("123456"));

        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.userLogin(request, mock(HttpServletRequest.class)));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void userLogin_userDisabled() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("test@example.com");
        request.setUserPassword("123456");

        User user = buildUser(100L, 1L, "test@example.com", UserConstant.TENANT_ADMIN_ROLE, 0);
        user.setUserPassword(encrypt("123456"));

        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.userLogin(request, mock(HttpServletRequest.class)));
        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), ex.getCode());
    }

    // ==================== 获取登录用户 ====================

    @Test
    void getLoginUser_success() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer valid-token");

        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn(100L);

        User user = buildUser(100L, 1L, "test@example.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        when(userMapper.selectById(100L)).thenReturn(user);

        User result = userService.getLoginUser(request);
        assertEquals(100L, result.getId());
    }

    @Test
    void getLoginUser_noToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getLoginUser(request));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
    }

    @Test
    void getLoginUser_invalidToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer bad-token");
        when(jwtUtil.parseToken("bad-token")).thenThrow(new RuntimeException("invalid"));

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getLoginUser(request));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
    }

    @Test
    void getLoginUser_userNotFound() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer valid-token");

        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn(100L);
        when(userMapper.selectById(100L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getLoginUser(request));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
    }

    @Test
    void getLoginUser_userDisabled() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer valid-token");

        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn(100L);

        User user = buildUser(100L, 1L, "test@example.com", UserConstant.TENANT_ADMIN_ROLE, 0);
        when(userMapper.selectById(100L)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getLoginUser(request));
        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), ex.getCode());
    }

    // ==================== 超管切换租户（M3-5 方案A） ====================

    @Test
    void getLoginUser_superAdmin_withTenantHeader_overridesTenantId() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer valid-token");
        when(request.getHeader(CommonConstant.TENANT_HEADER)).thenReturn("5");

        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn(100L);

        User user = buildUser(100L, null, "super@e.com", UserConstant.SUPER_ADMIN_ROLE, 1);
        when(userMapper.selectById(100L)).thenReturn(user);

        User result = userService.getLoginUser(request);
        assertEquals(5L, result.getTenantId());
    }

    @Test
    void getLoginUser_superAdmin_withoutHeader_keepsTenantId() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer valid-token");
        when(request.getHeader(CommonConstant.TENANT_HEADER)).thenReturn(null);

        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn(100L);

        User user = buildUser(100L, null, "super@e.com", UserConstant.SUPER_ADMIN_ROLE, 1);
        when(userMapper.selectById(100L)).thenReturn(user);

        User result = userService.getLoginUser(request);
        assertNull(result.getTenantId());
    }

    @Test
    void getLoginUser_normalUser_withTenantHeader_ignored() {
        // 普通用户不读取 X-Tenant-ID 头，即便请求携带该头也不应覆盖其 tenantId（防越权切换租户）
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer valid-token");

        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn(100L);

        User user = buildUser(100L, 1L, "admin@e.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        when(userMapper.selectById(100L)).thenReturn(user);

        User result = userService.getLoginUser(request);
        assertEquals(1L, result.getTenantId());
    }

    @Test
    void getLoginUser_superAdmin_invalidHeader_ignored() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.AUTHORIZATION_HEADER)).thenReturn("Bearer valid-token");
        when(request.getHeader(CommonConstant.TENANT_HEADER)).thenReturn("abc");

        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn(100L);

        User user = buildUser(100L, null, "super@e.com", UserConstant.SUPER_ADMIN_ROLE, 1);
        when(userMapper.selectById(100L)).thenReturn(user);

        User result = userService.getLoginUser(request);
        assertNull(result.getTenantId());
    }

    // ==================== VO 转换 ====================

    @Test
    void getUserVO_null() {
        assertNull(userService.getUserVO(null));
    }

    @Test
    void getUserVO_success() {
        User user = buildUser(1L, 2L, "e@e.com", "member", 1);
        user.setName("name");

        UserVO vo = userService.getUserVO(user);
        assertEquals(1L, vo.getId());
        assertEquals("name", vo.getName());
        assertEquals("e@e.com", vo.getEmail());
        assertEquals("member", vo.getRole());
        assertEquals(1, vo.getIsActive());
    }

    @Test
    void getLoginUserVO_success() {
        User user = buildUser(1L, 2L, "e@e.com", "member", 1);
        user.setName("name");

        LoginUserVO vo = userService.getLoginUserVO(user);
        assertEquals(1L, vo.getId());
        assertEquals("name", vo.getName());
        assertNull(vo.getToken());
    }

    // ==================== 成员管理 M3-2 ====================

    private TenantInvitation buildInvitation(Long id, Long tenantId, Long inviterId, String role, String status, Date expiresAt) {
        TenantInvitation invitation = new TenantInvitation();
        invitation.setId(id);
        invitation.setTenantId(tenantId);
        invitation.setInviterId(inviterId);
        invitation.setRole(role);
        invitation.setStatus(status);
        invitation.setExpiresAt(expiresAt);
        invitation.setAcceptedCount(0);
        invitation.setToken("tok-" + id);
        return invitation;
    }

    @Test
    void updateUser_crossTenant_denied() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setId(200L);
        req.setRole(UserConstant.TENANT_ADMIN_ROLE);

        User target = buildUser(200L, 999L, "x@e.com", "member", 1);
        when(userMapper.selectById(200L)).thenReturn(target);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateUser(1L, 100L, req));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateUser_cannotModifySuperAdmin() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setId(200L);
        req.setRole("member");

        User target = buildUser(200L, 1L, "x@e.com", UserConstant.SUPER_ADMIN_ROLE, 1);
        when(userMapper.selectById(200L)).thenReturn(target);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateUser(1L, 100L, req));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateUser_cannotDemoteLastAdmin() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setId(200L);
        req.setRole("member");

        User target = buildUser(200L, 1L, "x@e.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        when(userMapper.selectById(200L)).thenReturn(target);
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateUser(1L, 100L, req));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateUser_cannotChangeSelfRole() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setId(100L);
        req.setRole("member");

        User target = buildUser(100L, 1L, "x@e.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        when(userMapper.selectById(100L)).thenReturn(target);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateUser(1L, 100L, req));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateUser_cannotDisableSelf() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setId(100L);
        req.setIsActive(0);

        User target = buildUser(100L, 1L, "x@e.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        when(userMapper.selectById(100L)).thenReturn(target);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateUser(1L, 100L, req));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateUser_success() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setId(200L);
        req.setIsActive(0);

        User target = buildUser(200L, 1L, "x@e.com", "member", 1);
        when(userMapper.selectById(200L)).thenReturn(target);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        assertTrue(userService.updateUser(1L, 100L, req));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getIsActive());
    }

    @Test
    void createInvitation_success() {
        UserInviteRequest req = new UserInviteRequest();
        req.setName("新成员");
        req.setEmail("new@e.com");
        req.setRole(UserConstant.DEFAULT_ROLE);

        when(tenantInvitationMapper.insert(any(TenantInvitation.class))).thenAnswer(inv -> {
            inv.getArgument(0, TenantInvitation.class).setId(1L);
            return 1;
        });

        InviteResultVO vo = userService.createInvitation(1L, 100L, req);
        assertNotNull(vo.getToken());
        assertTrue(vo.getInviteUrl().startsWith("http://localhost:5173/register?token="));
        assertEquals(UserConstant.DEFAULT_ROLE, vo.getRole());
        assertNotNull(vo.getExpiresAt());

        ArgumentCaptor<TenantInvitation> captor = ArgumentCaptor.forClass(TenantInvitation.class);
        verify(tenantInvitationMapper).insert(captor.capture());
        assertEquals("pending", captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getAcceptedCount());
    }

    @Test
    void createInvitation_invalidRole() {
        UserInviteRequest req = new UserInviteRequest();
        req.setName("x");
        req.setEmail("x@e.com");
        req.setRole(UserConstant.SUPER_ADMIN_ROLE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.createInvitation(1L, 100L, req));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void getInviteInfo_success() {
        TenantInvitation invitation = buildInvitation(1L, 1L, 100L, "member", "pending",
                new Date(System.currentTimeMillis() + 3600_000));
        when(tenantInvitationMapper.selectOne(any(QueryWrapper.class))).thenReturn(invitation);
        when(userMapper.selectById(100L)).thenReturn(buildUser(100L, 1L, "admin@e.com", UserConstant.TENANT_ADMIN_ROLE, 1));
        Tenant tenant = new Tenant();
        tenant.setName("测试租户");
        when(tenantMapper.selectById(1L)).thenReturn(tenant);

        InviteInfoVO vo = userService.getInviteInfo("tok-1");
        assertEquals("测试租户", vo.getTenantName());
        assertEquals("member", vo.getRole());
        assertNotNull(vo.getInviterName());
    }

    @Test
    void getInviteInfo_expired() {
        TenantInvitation invitation = buildInvitation(1L, 1L, 100L, "member", "pending",
                new Date(System.currentTimeMillis() - 1000));
        when(tenantInvitationMapper.selectOne(any(QueryWrapper.class))).thenReturn(invitation);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getInviteInfo("tok-1"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void acceptInvitation_success() {
        TenantInvitation invitation = buildInvitation(1L, 1L, 100L, "member", "pending",
                new Date(System.currentTimeMillis() + 3600_000));
        when(tenantInvitationMapper.selectOne(any(QueryWrapper.class))).thenReturn(invitation);
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            inv.getArgument(0, User.class).setId(300L);
            return 1;
        });
        when(tenantInvitationMapper.updateById(any(TenantInvitation.class))).thenReturn(1);
        when(jwtUtil.generateToken(300L, 1L, "member")).thenReturn("new-token");

        UserAcceptInviteRequest req = new UserAcceptInviteRequest();
        req.setToken("tok-1");
        req.setName("接受者");
        req.setEmail("accept@e.com");
        req.setUserPassword("123456");

        LoginUserVO vo = userService.acceptInvitation(req);
        assertEquals(300L, vo.getId());
        assertEquals("new-token", vo.getToken());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getTenantId());
        assertEquals("member", captor.getValue().getRole());
        assertNotEquals("123456", captor.getValue().getUserPassword());
    }

    @Test
    void acceptInvitation_emailExists() {
        TenantInvitation invitation = buildInvitation(1L, 1L, 100L, "member", "pending",
                new Date(System.currentTimeMillis() + 3600_000));
        when(tenantInvitationMapper.selectOne(any(QueryWrapper.class))).thenReturn(invitation);
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        UserAcceptInviteRequest req = new UserAcceptInviteRequest();
        req.setToken("tok-1");
        req.setName("接受者");
        req.setEmail("exist@e.com");
        req.setUserPassword("123456");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.acceptInvitation(req));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void removeMember_cannotRemoveSelf() {
        UserRemoveRequest req = new UserRemoveRequest();
        req.setId(100L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.removeMember(1L, 100L, req));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void removeMember_cannotRemoveLastAdmin() {
        UserRemoveRequest req = new UserRemoveRequest();
        req.setId(200L);

        User target = buildUser(200L, 1L, "x@e.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        when(userMapper.selectById(200L)).thenReturn(target);
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.removeMember(1L, 100L, req));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    @Test
    void removeMember_success() {
        UserRemoveRequest req = new UserRemoveRequest();
        req.setId(200L);

        User target = buildUser(200L, 1L, "x@e.com", UserConstant.TENANT_ADMIN_ROLE, 1);
        when(userMapper.selectById(200L)).thenReturn(target);
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L);
        when(userMapper.deleteById(200L)).thenReturn(1);

        assertTrue(userService.removeMember(1L, 100L, req));
        verify(userMapper).deleteById(200L);
    }

    // ==================== 配额拦截（M3-5，对齐 WeKnora） ====================

    @Test
    void acceptInvitation_quotaExceeded() {
        TenantInvitation invitation = buildInvitation(1L, 1L, 100L, "member", "pending",
                new Date(System.currentTimeMillis() + 3600_000));
        when(tenantInvitationMapper.selectOne(any(QueryWrapper.class))).thenReturn(invitation);
        // 邮箱未注册，但租户成员数已达上限
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L, 1L);
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setMaxMembers(1);
        when(tenantMapper.selectById(1L)).thenReturn(tenant);

        UserAcceptInviteRequest req = new UserAcceptInviteRequest();
        req.setToken("tok-1");
        req.setName("接受者");
        req.setEmail("new@e.com");
        req.setUserPassword("123456");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.acceptInvitation(req));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    @Test
    void acceptInvitation_quotaUnlimited() {
        TenantInvitation invitation = buildInvitation(1L, 1L, 100L, "member", "pending",
                new Date(System.currentTimeMillis() + 3600_000));
        when(tenantInvitationMapper.selectOne(any(QueryWrapper.class))).thenReturn(invitation);
        // maxMembers<=0 视为不限：成员数任意都不拦截
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setMaxMembers(0);
        when(tenantMapper.selectById(1L)).thenReturn(tenant);
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            inv.getArgument(0, User.class).setId(300L);
            return 1;
        });
        when(tenantInvitationMapper.updateById(any(TenantInvitation.class))).thenReturn(1);
        when(jwtUtil.generateToken(300L, 1L, "member")).thenReturn("tok");

        UserAcceptInviteRequest req = new UserAcceptInviteRequest();
        req.setToken("tok-1");
        req.setName("接受者");
        req.setEmail("new@e.com");
        req.setUserPassword("123456");

        LoginUserVO vo = userService.acceptInvitation(req);
        assertEquals(300L, vo.getId());
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造测试用户（不含密码）。
     */
    private User buildUser(Long id, Long tenantId, String email, String role, int isActive) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setRole(role);
        user.setIsActive(isActive);
        return user;
    }

    /**
     * 密码加密（与实现保持一致的 MD5 + 盐值）。
     */
    private String encrypt(String password) {
        return DigestUtils.md5DigestAsHex((SALT + password).getBytes(StandardCharsets.UTF_8));
    }
}
