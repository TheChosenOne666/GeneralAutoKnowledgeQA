package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.common.ErrorCode;
import com.xiongda.constant.CommonConstant;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.BusinessException;
import com.xiongda.mapper.TenantMapper;
import com.xiongda.mapper.UserMapper;
import com.xiongda.model.dto.user.UserLoginRequest;
import com.xiongda.model.dto.user.UserRegisterRequest;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.entity.User;
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
    private JwtUtil jwtUtil;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "tenantMapper", tenantMapper);
        ReflectionTestUtils.setField(userService, "jwtUtil", jwtUtil);
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
