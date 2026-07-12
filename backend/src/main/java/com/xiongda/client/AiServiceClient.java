package com.xiongda.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

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
     * 流式问答 — 调用 Python AI 服务的 SSE 接口，返回 Flux 供 Controller 透传。
     */
    public Flux<DataBuffer> chatStream(
            String question,
            Long conversationId,
            List<Long> kbIds,
            String model,
            String mode,
            Long tenantId,
            List<Map<String, String>> history
    ) {
        Map<String, Object> requestBody = Map.of(
                "question", question,
                "conversation_id", conversationId != null ? String.valueOf(conversationId) : "",
                "kb_ids", kbIds != null ? kbIds.stream().map(String::valueOf).toList() : List.of(),
                "model", model != null ? model : "",
                "mode", mode != null ? mode : "rag",
                "tenant_id", tenantId != null ? String.valueOf(tenantId) : "",
                "history", history != null ? history : List.of()
        );

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
            Long docId, String filePath, String fileType, Long kbId, Long tenantId) {
        Map<String, Object> requestBody = Map.of(
                "doc_id", String.valueOf(docId),
                "file_path", filePath,
                "file_type", fileType,
                "kb_id", String.valueOf(kbId),
                "tenant_id", String.valueOf(tenantId)
        );

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
