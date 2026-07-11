package com.xiongda.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 模型配置实体 — LLM / Embedding / Rerank。
 * user_id 为空表示租户级默认配置。
 */
@Entity
@Table(name = "ai_configs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AiConfig {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId; // null = 租户默认

    // LLM
    @Column(name = "llm_provider", length = 50)
    private String llmProvider = "volcengine";

    @Column(name = "llm_model", length = 100)
    private String llmModel = "doubao-pro";

    @Column(name = "llm_api_key", length = 500)
    private String llmApiKey = "";

    @Column(name = "llm_base_url", length = 500)
    private String llmBaseUrl = "";

    @Column(name = "llm_temperature")
    private Double llmTemperature = 0.7;

    @Column(name = "llm_max_tokens")
    private Integer llmMaxTokens = 2048;

    // Embedding
    @Column(name = "embedding_provider", length = 50)
    private String embeddingProvider = "volcengine";

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel = "doubao-embedding";

    @Column(name = "embedding_api_key", length = 500)
    private String embeddingApiKey = "";

    @Column(name = "embedding_base_url", length = 500)
    private String embeddingBaseUrl = "";

    @Column(name = "embedding_dimension")
    private Integer embeddingDimension = 1536;

    // Rerank (可选)
    @Column(name = "rerank_provider", length = 50)
    private String rerankProvider;

    @Column(name = "rerank_model", length = 100)
    private String rerankModel;

    @Column(name = "rerank_api_key", length = 500)
    private String rerankApiKey;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
