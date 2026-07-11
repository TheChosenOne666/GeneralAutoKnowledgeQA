package com.xiongda.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 知识库实体 — scope=shared 共享 / scope=personal 个人。
 */
@Entity
@Table(name = "knowledge_bases")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class KnowledgeBase {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 20)
    private String scope = "shared"; // shared / personal

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "document_count")
    private Integer documentCount = 0;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
