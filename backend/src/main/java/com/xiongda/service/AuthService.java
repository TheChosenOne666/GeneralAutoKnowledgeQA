package com.xiongda.service;

import com.xiongda.dto.auth.AuthDto.*;
import com.xiongda.entity.Tenant;
import com.xiongda.entity.User;
import com.xiongda.repository.TenantRepository;
import com.xiongda.repository.UserRepository;
import com.xiongda.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 认证服务 — 登录、注册。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new RuntimeException("该邮箱已注册");
        }

        // 为新用户创建租户
        var tenant = Tenant.builder()
                .name(req.getName() + "的租户")
                .slug("tenant-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        tenantRepository.save(tenant);

        var user = User.builder()
                .tenantId(tenant.getId())
                .name(req.getName())
                .email(req.getEmail())
                .hashedPassword(passwordEncoder.encode(req.getPassword()))
                .role("tenant_admin") // 首个用户默认为租户管理员
                .isActive(true)
                .build();
        userRepository.save(user);

        return buildTokenResponse(user);
    }

    public TokenResponse login(LoginRequest req) {
        var user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new RuntimeException("邮箱或密码错误"));

        if (!passwordEncoder.matches(req.getPassword(), user.getHashedPassword())) {
            throw new RuntimeException("邮箱或密码错误");
        }

        if (!user.getIsActive()) {
            throw new RuntimeException("用户已被停用");
        }

        return buildTokenResponse(user);
    }

    public UserBrief getUserBrief(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return toUserBrief(user);
    }

    private TokenResponse buildTokenResponse(User user) {
        var token = jwtUtil.generateToken(user.getId(), user.getTenantId(), user.getRole());
        return TokenResponse.builder()
                .accessToken(token)
                .tokenType("bearer")
                .user(toUserBrief(user))
                .build();
    }

    private UserBrief toUserBrief(User user) {
        return UserBrief.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .tenantId(user.getTenantId() != null ? user.getTenantId().toString() : null)
                .avatarUrl(user.getAvatarUrl())
                .build();
    }
}
