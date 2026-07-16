package com.xiongda.service;

import com.xiongda.common.ErrorCode;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.enums.KbScopeEnum;
import org.apache.commons.lang3.StringUtils;

/**
 * 知识库权限判定工具 — 集中 RBAC 数据级规则，便于单测。
 *
 * <p>规则（见 design.md §3 / §4.2）：
 * <ul>
 *   <li>共享库（shared）：仅租户管理员 / 平台超管可写（创建、上传、删除文档）</li>
 *   <li>个人库（personal）：仅 owner 可写</li>
 *   <li>读操作（列表、问答）对租户内全员开放，不在此限制</li>
 * </ul>
 */
public final class KbPermission {

    private KbPermission() {
    }

    /**
     * 是否为租户管理员或平台超管（拥有共享库写权限）。
     */
    public static boolean isTenantAdminOrSuper(String role) {
        return UserConstant.TENANT_ADMIN_ROLE.equals(role)
                || UserConstant.SUPER_ADMIN_ROLE.equals(role);
    }

    /**
     * 校验能否创建知识库：共享库仅租户管理员 / 平台超管可创建。
     */
    public static void assertCanCreate(String scope, String role) {
        String resolved = StringUtils.isNotBlank(scope) ? scope : "personal";
        if (KbScopeEnum.SHARED.getValue().equals(resolved) && !isTenantAdminOrSuper(role)) {
            ThrowUtils.throwIf(true, ErrorCode.NO_AUTH_ERROR, "仅租户管理员可创建共享知识库");
        }
    }

    /**
     * 校验能否写入知识库（上传 / 删除文档）。
     *
     * <p>判定顺序对齐 业界的 own-KB 优先模型（kb_access.go 第一步
     * {@code kb.TenantID == tenantID}）：
     * <ol>
     *   <li>平台超管（super_admin）跨租户完全放行</li>
     *   <li>其余角色：调用者租户必须等于 KB 租户（租户隔离第一维度）</li>
     *   <li>共享库仅租户管理员，个人库仅 owner</li>
     * </ol>
     */
    public static void assertCanWrite(KnowledgeBase kb, Long userId, Long callerTenantId, String role) {
        ThrowUtils.throwIf(kb == null, ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        // 平台超管跨租户完全放行（对应 业界的 SystemAdmin 跨租户超管）
        if (UserConstant.SUPER_ADMIN_ROLE.equals(role)) {
            return;
        }
        // 第一维度：租户隔离（对齐 业界 own-KB（自有 KB）判定 kb.TenantID == tenantID）
        ThrowUtils.throwIf(!callerTenantId.equals(kb.getTenantId()),
                ErrorCode.NO_AUTH_ERROR, "无权操作其他租户的知识库");
        if (KbScopeEnum.SHARED.getValue().equals(kb.getScope())) {
            ThrowUtils.throwIf(!isTenantAdminOrSuper(role),
                    ErrorCode.NO_AUTH_ERROR, "共享知识库仅租户管理员可操作");
        } else {
            ThrowUtils.throwIf(!userId.equals(kb.getOwnerId()),
                    ErrorCode.NO_AUTH_ERROR, "无权操作他人的个人知识库");
        }
    }
}
