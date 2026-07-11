package com.xiongda.controller;

import com.xiongda.dto.member.MemberDto.*;
import com.xiongda.entity.User;
import com.xiongda.repository.UserRepository;
import com.xiongda.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 成员管理控制器 — 仅租户管理员可访问。
 */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final UserRepository userRepository;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping
    public ResponseEntity<List<MemberOut>> listMembers() {
        var tenantId = securityContextUtil.getCurrentTenantId();
        var members = userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .map(this::toMemberOut)
                .toList();
        return ResponseEntity.ok(members);
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<MemberOut> updateMember(
            @PathVariable String userId,
            @RequestBody MemberUpdate body
    ) {
        var user = userRepository.findById(java.util.UUID.fromString(userId))
                .orElseThrow(() -> new RuntimeException("成员不存在"));

        if (body.getRole() != null) user.setRole(body.getRole());
        if (body.getIsActive() != null) user.setIsActive(body.getIsActive());
        userRepository.save(user);

        return ResponseEntity.ok(toMemberOut(user));
    }

    private MemberOut toMemberOut(User user) {
        return MemberOut.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .avatarUrl(user.getAvatarUrl())
                .lastActiveAt(user.getLastActiveAt() != null ? user.getLastActiveAt().toString() : null)
                .createdAt(user.getCreatedAt().toString())
                .build();
    }
}
