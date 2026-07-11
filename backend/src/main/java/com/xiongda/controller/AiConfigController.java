package com.xiongda.controller;

import com.xiongda.dto.config.AiConfigDto.*;
import com.xiongda.entity.AiConfig;
import com.xiongda.repository.AiConfigRepository;
import com.xiongda.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

/**
 * AI 模型配置控制器。
 */
@RestController
@RequestMapping("/api/ai-config")
@RequiredArgsConstructor
public class AiConfigController {

    private final AiConfigRepository aiConfigRepository;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping
    public ResponseEntity<AiConfigOut> getConfig() {
        var tenantId = securityContextUtil.getCurrentTenantId();
        var userId = securityContextUtil.getCurrentUserId();

        // 优先查用户级配置，再查租户级默认
        var config = aiConfigRepository.findByTenantIdAndUserId(tenantId, userId)
                .or(() -> aiConfigRepository.findByTenantIdAndUserIdIsNull(tenantId))
                .orElseGet(() -> AiConfig.builder().tenantId(tenantId).build());

        return ResponseEntity.ok(toOut(config));
    }

    @PutMapping
    public ResponseEntity<AiConfigOut> updateConfig(@RequestBody AiConfigUpdate body) {
        var tenantId = securityContextUtil.getCurrentTenantId();
        var userId = securityContextUtil.getCurrentUserId();

        var config = aiConfigRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseGet(() -> {
                    var c = AiConfig.builder().tenantId(tenantId).userId(userId).build();
                    return aiConfigRepository.save(c);
                });

        // 按需更新字段
        if (body.getLlmProvider() != null) config.setLlmProvider(body.getLlmProvider());
        if (body.getLlmModel() != null) config.setLlmModel(body.getLlmModel());
        if (body.getLlmApiKey() != null) config.setLlmApiKey(body.getLlmApiKey());
        if (body.getLlmBaseUrl() != null) config.setLlmBaseUrl(body.getLlmBaseUrl());
        if (body.getLlmTemperature() != null) config.setLlmTemperature(body.getLlmTemperature());
        if (body.getLlmMaxTokens() != null) config.setLlmMaxTokens(body.getLlmMaxTokens());
        if (body.getEmbeddingProvider() != null) config.setEmbeddingProvider(body.getEmbeddingProvider());
        if (body.getEmbeddingModel() != null) config.setEmbeddingModel(body.getEmbeddingModel());
        if (body.getEmbeddingApiKey() != null) config.setEmbeddingApiKey(body.getEmbeddingApiKey());
        if (body.getEmbeddingBaseUrl() != null) config.setEmbeddingBaseUrl(body.getEmbeddingBaseUrl());
        if (body.getEmbeddingDimension() != null) config.setEmbeddingDimension(body.getEmbeddingDimension());
        if (body.getRerankProvider() != null) config.setRerankProvider(body.getRerankProvider());
        if (body.getRerankModel() != null) config.setRerankModel(body.getRerankModel());
        if (body.getRerankApiKey() != null) config.setRerankApiKey(body.getRerankApiKey());

        aiConfigRepository.save(config);

        return ResponseEntity.ok(toOut(config));
    }

    private AiConfigOut toOut(AiConfig c) {
        return AiConfigOut.builder()
                .llmProvider(c.getLlmProvider())
                .llmModel(c.getLlmModel())
                .llmBaseUrl(c.getLlmBaseUrl())
                .llmTemperature(c.getLlmTemperature())
                .llmMaxTokens(c.getLlmMaxTokens())
                .embeddingProvider(c.getEmbeddingProvider())
                .embeddingModel(c.getEmbeddingModel())
                .embeddingBaseUrl(c.getEmbeddingBaseUrl())
                .embeddingDimension(c.getEmbeddingDimension())
                .rerankProvider(c.getRerankProvider())
                .rerankModel(c.getRerankModel())
                .hasRerank(c.getRerankProvider() != null && !c.getRerankProvider().isBlank())
                .build();
    }
}
