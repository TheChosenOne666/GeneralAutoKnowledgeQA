package com.xiongda.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息实体 — 会话中的每条消息（用户提问 / AI 回答）。
 */
@Entity
@Table(name = "messages")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false, length = 20)
    private String role; // user / assistant

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** AI 回答的引用来源 (JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> sources;

    /** 使用的模型名称 */
    @Column(length = 100)
    private String model;

    /** 使用的知识库 ID 列表 (JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> kbIds;

    @CreationTimestamp
    private Instant createdAt;
}
