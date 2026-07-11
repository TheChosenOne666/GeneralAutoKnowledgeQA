package com.xiongda.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 文档实体 — 知识库中的文件，含处理状态。
 */
@Entity
@Table(name = "documents")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType; // pdf / docx / md / txt

    @Column(name = "file_size")
    private Long fileSize = 0L;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(length = 20)
    private String status = "pending"; // pending / parsing / embedding / ready / failed

    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    @Column(name = "error_msg", columnDefinition = "text")
    private String errorMsg;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
