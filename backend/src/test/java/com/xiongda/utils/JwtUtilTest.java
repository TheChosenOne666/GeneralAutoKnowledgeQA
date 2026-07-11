package com.xiongda.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具类单元测试 — 验证 Token 生成、解析、过期、无效场景。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-at-least-32-characters-long!!");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);
    }

    @Test
    void generateAndParseToken_success() {
        String token = jwtUtil.generateToken(100L, 200L, "tenant_admin");
        assertNotNull(token);

        Claims claims = jwtUtil.parseToken(token);
        assertEquals(100L, jwtUtil.getUserId(claims));
        assertEquals(200L, jwtUtil.getTenantId(claims));
        assertEquals("tenant_admin", jwtUtil.getRole(claims));
    }

    @Test
    void generateAndParseToken_tenantIdNull() {
        String token = jwtUtil.generateToken(1L, null, "member");
        Claims claims = jwtUtil.parseToken(token);
        assertEquals(1L, jwtUtil.getUserId(claims));
        assertNull(jwtUtil.getTenantId(claims));
        assertEquals("member", jwtUtil.getRole(claims));
    }

    @Test
    void parseToken_expired() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1000L);
        String token = jwtUtil.generateToken(1L, null, "member");
        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parseToken(token));
    }

    @Test
    void parseToken_invalid() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("invalid.token.here"));
    }
}
