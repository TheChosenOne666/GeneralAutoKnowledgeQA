package com.xiongda.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import com.xiongda.model.entity.AiConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务客户端 — Java 后端通过 HTTP 调用 Python AI 服务。
 *
 * <p>Python AI 服务 (FastAPI + LangChain) 提供以下能力：
 * <ul>
 *   <li>RAG 检索 + LLM 流式生成</li>
 *   <li>文档处理（解析 → 分块 → 向量化）</li>
 *   <li>缓存失效（文档变更后清 L1 检索结果）</li>
 * </ul>
 *
 * <p>M3-3：调用时将用户在界面配置的 AI 模型（含 API Key）以 snake_case 字典 {@code ai_config}
 * 透传给 Python，供其真正消费用户配置并识别「模型配置错误」。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Component
public class AiServiceClient {

    private final WebClient webClient;

    public AiServiceClient(@Value("${ai-service.base-url}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 将 AI 配置实体转为 Python 所需的 snake_case 字典（ai_config）。
     * 返回 null 表示无配置（Python 将按自身 env 兜底并抛出模型配置错误）。
     */
    public static Map<String, Object> toAiConfigMap(AiConfig cfg) {
        if (cfg == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        putIfNotNull(m, "llm_provider", cfg.getLlmProvider());
        putIfNotNull(m, "llm_model", cfg.getLlmModel());
        putIfNotNull(m, "llm_api_key", cfg.getLlmApiKey());
        putIfNotNull(m, "llm_base_url", cfg.getLlmBaseUrl());
        putIfNotNull(m, "embedding_provider", cfg.getEmbeddingProvider());
        putIfNotNull(m, "embedding_model", cfg.getEmbeddingModel());
        putIfNotNull(m, "embedding_api_key", cfg.getEmbeddingApiKey());
        putIfNotNull(m, "embedding_base_url", cfg.getEmbeddingBaseUrl());
        if (cfg.getEmbeddingDimension() != null) {
            m.put("embedding_dimension", cfg.getEmbeddingDimension());
        }
        putIfNotNull(m, "rerank_provider", cfg.getRerankProvider());
        putIfNotNull(m, "rerank_model", cfg.getRerankModel());
        putIfNotNull(m, "rerank_api_key", cfg.getRerankApiKey());
        return m;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    /**
     * 流式问答 — 调用 Python AI 服务的 SSE 接口，返回 Flux 供 Controller 透传。
     */
    public Flux<DataBuffer> chatStream(
            String question,
            Long conversationId,
            List<Long> kbIds,
            String model,
            String mode,
            Long tenantId,
            List<Map<String, String>> history,
            Map<String, Object> aiConfig
    ) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("question", question);
        requestBody.put("conversation_id", conversationId != null ? String.valueOf(conversationId) : "");
        requestBody.put("kb_ids", kbIds != null ? kbIds.stream().map(String::valueOf).toList() : List.of());
        requestBody.put("model", model != null ? model : "");
        requestBody.put("mode", mode != null ? mode : "rag");
        requestBody.put("tenant_id", tenantId != null ? String.valueOf(tenantId) : "");
        requestBody.put("history", history != null ? history : List.of());
        requestBody.put("ai_config", aiConfig);

        return webClient.post()
                .uri("/ai/chat/stream")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(DataBuffer.class);
    }

    /**
     * 触发文档处理 — 通知 Python AI 服务处理上传的文档。
     */
    public Map<String, Object> processDocument(
            Long docId, String filePath, String fileType, Long kbId, Long tenantId, Map<String, Object> aiConfig) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("doc_id", String.valueOf(docId));
        requestBody.put("file_path", filePath);
        requestBody.put("file_type", fileType);
        requestBody.put("kb_id", String.valueOf(kbId));
        requestBody.put("tenant_id", String.valueOf(tenantId));
        requestBody.put("ai_config", aiConfig);

        return webClient.post()
                .uri("/ai/document/process")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    /**
     * 清检索结果缓存（L1）— 文档变更后调用，清该租户下 retrieval:{tenant}:*。
     * Python 服务不可用时忽略，不阻塞文档处理主流程。
     */
    public void invalidateCache(Long tenantId) {
        Map<String, Object> body = Map.of(
                "tenant_id", tenantId != null ? String.valueOf(tenantId) : "",
                "scope", "retrieval"
        );
        try {
            webClient.post()
                    .uri("/ai/cache/invalidate")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("清检索缓存失败，忽略: {}", e.getMessage());
        }
    }
}
