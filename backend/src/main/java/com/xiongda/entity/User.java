package com.xiongda.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户实体 — 支持 3 种角色。
 */
@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId; // super_admin 为 null

    @Column(nullable = false, length = 100)
    private String name;

    @Column(unique = true, nullable = false, length = 255)
    private String email;

    @Column(name = "hashed_password", nullable = false, length = 255)
    private String hashedPassword;

    @Column(length = 30)
    private String role = "member"; // member / tenant_admin / super_admin

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
