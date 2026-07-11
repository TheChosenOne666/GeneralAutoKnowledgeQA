package com.xiongda.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * AI 服务客户端 — Java 后端通过 HTTP 调用 Python AI 服务。
 *
 * <p>Python AI 服务 (FastAPI + LangChain) 提供以下能力：
 * <ul>
 *   <li>RAG 检索 + LLM 流式生成</li>
 *   <li>文档处理（解析 → 分块 → 向量化）</li>
 * </ul>
 */
@Component
public class AiServiceClient {

    private final WebClient webClient;

    public AiServiceClient(@Value("${ai-service.base-url}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 流式问答 — 调用 Python AI 服务的 SSE 接口，返回 Flux 供 Controller 透传。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param kbIds          知识库 ID 列表
     * @param model          模型名称
     * @param mode           模式 (rag / search)
     * @param tenantId       租户 ID
     * @return SSE 事件流
     */
    public Flux<String> chatStream(
            String question,
            UUID conversationId,
            java.util.List<UUID> kbIds,
            String model,
            String mode,
            UUID tenantId
    ) {
        var requestBody = Map.of(
                "question", question,
                "conversation_id", conversationId != null ? conversationId.toString() : "",
                "kb_ids", kbIds != null ? kbIds.stream().map(UUID::toString).toList() : java.util.List.of(),
                "model", model != null ? model : "",
                "mode", mode != null ? mode : "rag",
                "tenant_id", tenantId != null ? tenantId.toString() : ""
        );

        return webClient.post()
                .uri("/ai/chat/stream")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /**
     * 触发文档处理 — 通知 Python AI 服务处理上传的文档。
     *
     * @param docId     文档 ID
     * @param filePath  文件路径
     * @param fileType  文件类型
     * @param kbId      知识库 ID
     * @param tenantId  租户 ID
     * @return 处理结果（分块数量等）
     */
    public Map<String, Object> processDocument(
            UUID docId,
            String filePath,
            String fileType,
            UUID kbId,
            UUID tenantId
    ) {
        var requestBody = Map.of(
                "doc_id", docId.toString(),
                "file_path", filePath,
                "file_type", fileType,
                "kb_id", kbId.toString(),
                "tenant_id", tenantId.toString()
        );

        return webClient.post()
                .uri("/ai/document/process")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }
}
