package com.xiongda.controller;

import com.xiongda.dto.auth.AuthDto.*;
import com.xiongda.security.SecurityContextUtil;
import com.xiongda.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 — 登录、注册、获取当前用户。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SecurityContextUtil securityContextUtil;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    @GetMapping("/me")
    public ResponseEntity<UserBrief> me() {
        var userId = securityContextUtil.getCurrentUserId();
        return ResponseEntity.ok(authService.getUserBrief(userId));
    }
}
