package com.xiongda.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 租户实体 — 多租户隔离顶层。
 */
@Entity
@Table(name = "tenants")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(unique = true, nullable = false, length = 100)
    private String slug;

    @Column(length = 20)
    private String status = "active"; // active / suspended

    private Integer maxMembers = 50;

    private Integer maxDocuments = 1000;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
