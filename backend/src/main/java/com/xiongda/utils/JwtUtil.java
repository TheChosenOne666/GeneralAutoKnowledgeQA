package com.xiongda.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 — 生成/验证/解析 Token。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token。
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID（可空）
     * @param role     角色
     */
    public String generateToken(Long userId, Long tenantId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenant_id", tenantId != null ? String.valueOf(tenantId) : null)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getKey())
                .compact();
    }

    /**
     * 解析 Token，返回 Claims。失败抛异常。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    public Long getTenantId(Claims claims) {
        String tid = claims.get("tenant_id", String.class);
        return tid != null ? Long.parseLong(tid) : null;
    }

    public String getRole(Claims claims) {
        return claims.get("role", String.class);
    }
}
