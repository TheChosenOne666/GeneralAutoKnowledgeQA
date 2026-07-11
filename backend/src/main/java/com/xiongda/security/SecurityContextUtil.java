package com.xiongda.security;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 安全上下文工具 — 从当前线程获取用户信息。
 */
@Component
public class SecurityContextUtil {

    /**
     * 获取当前用户 ID。
     */
    public UUID getCurrentUserId() {
        var auth = getAuth();
        if (auth == null) return null;
        return UUID.fromString(auth.getName());
    }

    /**
     * 获取当前租户 ID。
     */
    public UUID getCurrentTenantId() {
        var auth = getAuth();
        if (auth == null || auth.getDetails() == null) return null;
        var claims = (Claims) auth.getDetails();
        String tid = claims.get("tenant_id", String.class);
        return tid != null ? UUID.fromString(tid) : null;
    }

    /**
     * 获取当前用户角色。
     */
    public String getCurrentRole() {
        var auth = getAuth();
        if (auth == null || auth.getDetails() == null) return null;
        var claims = (Claims) auth.getDetails();
        return claims.get("role", String.class);
    }

    private Authentication getAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
