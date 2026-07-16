package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiongda.common.ErrorCode;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.BusinessException;
import com.xiongda.mapper.DocumentMapper;
import com.xiongda.mapper.TenantMapper;
import com.xiongda.mapper.UserMapper;
import com.xiongda.model.dto.tenant.TenantCreateRequest;
import com.xiongda.model.dto.tenant.TenantQuotaRequest;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.TenantVO;
import com.xiongda.service.impl.TenantServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 租户服务实现单元测试 — 覆盖平台超管的租户管理（创建/启用停用/配额/列表统计）。
 *
 * <p>使用 Mockito 纯单元测试，mock 掉 Mapper，不依赖数据库。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceImplTest {

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private DocumentMapper documentMapper;

    private TenantServiceImpl tenantService;

    @BeforeEach
    void setUp() {
        tenantService = new TenantServiceImpl();
        ReflectionTestUtils.setField(tenantService, "baseMapper", tenantMapper);
        ReflectionTestUtils.setField(tenantService, "userMapper", userMapper);
        ReflectionTestUtils.setField(tenantService, "documentMapper", documentMapper);
    }

    private User user(Long id, String email, String role, Long tenantId) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setRole(role);
        u.setTenantId(tenantId);
        return u;
    }

    private Tenant tenant(Long id, String status, Integer maxMembers, Integer maxDocuments) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setStatus(status);
        t.setMaxMembers(maxMembers);
        t.setMaxDocuments(maxDocuments);
        return t;
    }

    // ==================== 创建租户 ====================

    @Test
    void createTenant_success_setsAdmin() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setName("租户A");
        req.setSlug("tenant-a");
        req.setMaxMembers(10);
        req.setMaxDocuments(100);
        req.setAdminEmail("admin@x.com");

        when(tenantMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L); // slug 不重复
        User admin = user(100L, "admin@x.com", UserConstant.DEFAULT_ROLE, 1L);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(admin);
        when(tenantMapper.insert(any(Tenant.class))).thenAnswer(inv -> {
            ((Tenant) inv.getArgument(0)).setId(999L);
            return 1;
        });

        TenantVO vo = tenantService.createTenant(req);

        assertNotNull(vo);
        assertEquals("active", vo.getStatus());
        // 已存在用户被设为新租户首个管理员（对标业界成熟方案 EnsureOwner）
        assertEquals(999L, admin.getTenantId());
        assertEquals(UserConstant.TENANT_ADMIN_ROLE, admin.getRole());
        verify(userMapper).updateById(admin);
    }

    @Test
    void createTenant_slugExists_throws() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setName("租户A");
        req.setSlug("tenant-a");
        req.setAdminEmail("admin@x.com");
        when(tenantMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.createTenant(req));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void createTenant_adminNotFound_throws() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setName("租户A");
        req.setSlug("tenant-a");
        req.setAdminEmail("nobody@x.com");
        when(tenantMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.createTenant(req));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void createTenant_adminIsSuperAdmin_throws() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setName("租户A");
        req.setSlug("tenant-a");
        req.setAdminEmail("sa@x.com");
        when(tenantMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        User sa = user(1L, "sa@x.com", UserConstant.SUPER_ADMIN_ROLE, null);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(sa);

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.createTenant(req));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    // ==================== 启用 / 停用 ====================

    @Test
    void setStatus_success() {
        Tenant t = tenant(5L, "active", 50, 1000);
        when(tenantMapper.selectById(5L)).thenReturn(t);

        TenantVO vo = tenantService.setStatus(5L, "suspended");
        assertEquals("suspended", vo.getStatus());
        verify(tenantMapper).updateById(t);
    }

    @Test
    void setStatus_invalid_throws() {
        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.setStatus(5L, "deleted"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    // ==================== 配额设置 ====================

    @Test
    void setQuota_success() {
        Tenant t = tenant(5L, "active", 50, 1000);
        when(tenantMapper.selectById(5L)).thenReturn(t);
        TenantQuotaRequest req = new TenantQuotaRequest();
        req.setMaxMembers(20);
        req.setMaxDocuments(500);

        TenantVO vo = tenantService.setQuota(5L, req);
        assertEquals(20, vo.getMaxMembers());
        assertEquals(500, vo.getMaxDocuments());
        verify(tenantMapper).updateById(t);
    }

    // ==================== 列表统计 ====================

    @Test
    void listTenants_countsMembersAndDocs() {
        Tenant t = tenant(5L, "active", 50, 1000);
        Page<Tenant> page = new Page<>();
        page.setRecords(Arrays.asList(t));
        page.setTotal(1);
        when(tenantMapper.selectPage(any(), any())).thenReturn(page);
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(3L);
        when(documentMapper.selectCount(any(QueryWrapper.class))).thenReturn(7L);

        Page<TenantVO> result = tenantService.listTenants(1, 10);
        assertEquals(1, result.getRecords().size());
        assertEquals(3L, result.getRecords().get(0).getMemberCount());
        assertEquals(7L, result.getRecords().get(0).getDocCount());
    }
}
