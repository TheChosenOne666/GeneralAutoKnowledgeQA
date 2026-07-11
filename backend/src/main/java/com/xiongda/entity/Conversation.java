package com.xiongda.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话实体 — 一个会话包含多条消息。
 */
@Entity
@Table(name = "conversations")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(length = 200)
    private String title = "新对话";

    @Column(name = "message_count")
    private Integer messageCount = 0;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
