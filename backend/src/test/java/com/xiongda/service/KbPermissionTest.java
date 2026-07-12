package com.xiongda.service;

import com.xiongda.common.ErrorCode;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.BusinessException;
import com.xiongda.model.entity.KnowledgeBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库权限判定工具单元测试 — 覆盖共享库/个人库的创建与写权限规则。
 *
 * <p>规则对齐 WeKnora 的租户角色 + KB 归属数据级 RBAC：
 * 共享库仅租户管理员 / 平台超管可写，个人库仅 owner 可写。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
class KbPermissionTest {

    private KnowledgeBase kb(String scope, Long ownerId, Long tenantId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setScope(scope);
        kb.setOwnerId(ownerId);
        kb.setTenantId(tenantId);
        return kb;
    }

    // ==================== 角色判定 ====================

    @Test
    void isTenantAdminOrSuper_tenantAdmin() {
        assertTrue(KbPermission.isTenantAdminOrSuper(UserConstant.TENANT_ADMIN_ROLE));
    }

    @Test
    void isTenantAdminOrSuper_superAdmin() {
        assertTrue(KbPermission.isTenantAdminOrSuper(UserConstant.SUPER_ADMIN_ROLE));
    }

    @Test
    void isTenantAdminOrSuper_member() {
        assertFalse(KbPermission.isTenantAdminOrSuper(UserConstant.DEFAULT_ROLE));
    }

    // ==================== 创建知识库 ====================

    @Test
    void assertCanCreate_sharedAsTenantAdmin_ok() {
        assertDoesNotThrow(() -> KbPermission.assertCanCreate("shared", UserConstant.TENANT_ADMIN_ROLE));
    }

    @Test
    void assertCanCreate_sharedAsSuperAdmin_ok() {
        assertDoesNotThrow(() -> KbPermission.assertCanCreate("shared", UserConstant.SUPER_ADMIN_ROLE));
    }

    @Test
    void assertCanCreate_sharedAsMember_denied() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KbPermission.assertCanCreate("shared", UserConstant.DEFAULT_ROLE));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void assertCanCreate_personalAsMember_ok() {
        assertDoesNotThrow(() -> KbPermission.assertCanCreate("personal", UserConstant.DEFAULT_ROLE));
    }

    @Test
    void assertCanCreate_nullScopeDefaultPersonal_ok() {
        assertDoesNotThrow(() -> KbPermission.assertCanCreate(null, UserConstant.DEFAULT_ROLE));
    }

    // ==================== 写入知识库 ====================

    @Test
    void assertCanWrite_personalOwner_ok() {
        assertDoesNotThrow(() -> KbPermission.assertCanWrite(kb("personal", 100L, 1L), 100L, 1L, UserConstant.DEFAULT_ROLE));
    }

    @Test
    void assertCanWrite_personalNotOwner_denied() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KbPermission.assertCanWrite(kb("personal", 999L, 1L), 100L, 1L, UserConstant.DEFAULT_ROLE));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void assertCanWrite_sharedAsTenantAdmin_ok() {
        assertDoesNotThrow(() -> KbPermission.assertCanWrite(kb("shared", 999L, 1L), 100L, 1L, UserConstant.TENANT_ADMIN_ROLE));
    }

    @Test
    void assertCanWrite_sharedAsSuperAdmin_ok() {
        assertDoesNotThrow(() -> KbPermission.assertCanWrite(kb("shared", 999L, 1L), 100L, 1L, UserConstant.SUPER_ADMIN_ROLE));
    }

    @Test
    void assertCanWrite_sharedAsMember_denied() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KbPermission.assertCanWrite(kb("shared", 999L, 1L), 100L, 1L, UserConstant.DEFAULT_ROLE));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void assertCanWrite_nullKb_notFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KbPermission.assertCanWrite(null, 100L, 1L, UserConstant.TENANT_ADMIN_ROLE));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
    }

    // ==================== 租户隔离（对齐 WeKnora own-KB 判定） ====================

    @Test
    void assertCanWrite_tenantAdminCrossTenant_denied() {
        // 租户 1 的 tenant_admin 不能写租户 2 的共享库
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KbPermission.assertCanWrite(kb("shared", 999L, 2L), 100L, 1L, UserConstant.TENANT_ADMIN_ROLE));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void assertCanWrite_memberCrossTenantPersonal_denied() {
        // 租户 1 的 member 即使 ownerId 匹配，也不能写租户 2 的个人库
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KbPermission.assertCanWrite(kb("personal", 100L, 2L), 100L, 1L, UserConstant.DEFAULT_ROLE));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void assertCanWrite_superAdminCrossTenant_ok() {
        // 平台超管跨租户完全放行
        assertDoesNotThrow(() -> KbPermission.assertCanWrite(kb("shared", 999L, 2L), 100L, 1L, UserConstant.SUPER_ADMIN_ROLE));
    }
}
