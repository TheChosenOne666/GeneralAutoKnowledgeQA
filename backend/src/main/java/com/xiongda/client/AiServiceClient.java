package com.xiongda.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import com.xiongda.model.entity.AiConfig;
import java.util.ArrayList;
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

    /**
     * 响应体最大缓冲（字节）。默认仅 256KB，超出会抛 DataBufferLimitException。
     * 文档处理接口会把全文（大 PDF 可达数百 KB~数 MB）随响应返回，故放宽到 20MB，
     * 避免大文档处理响应被缓冲上限截断（集成联调中 394 页 PDF 即触发该问题）。
     */
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

    private final WebClient webClient;

    public AiServiceClient(@Value("${ai-service.base-url}") String baseUrl) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .build();
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
     *
     * @param imagePaths       问答携带的图片绝对路径列表（M5-9 多模态，Python 转 base64 调 vision）
     * @param attachmentPaths  问答携带的通用文档绝对路径列表（M5-9 一次性文档问答，Python 提取文本拼上下文）
     */
    public Flux<DataBuffer> chatStream(
            String question,
            Long conversationId,
            List<Long> kbIds,
            String model,
            String mode,
            Long tenantId,
            List<Map<String, String>> history,
            Map<String, Object> aiConfig,
            List<String> imagePaths,
            List<String> attachmentPaths,
            String retrievalConfig
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
        // M5-9 多模态问答：图片 / 通用文档绝对路径透传给 Python
        requestBody.put("image_paths", imagePaths != null ? imagePaths : List.of());
        requestBody.put("attachment_paths", attachmentPaths != null ? attachmentPaths : List.of());
        // M6-1：租户级检索配置 JSON 字符串透传给 Python（NULL 走 Python settings 默认值）
        requestBody.put("retrieval_config", retrievalConfig);
        if (aiConfig != null) {
            String llmKey = (String) aiConfig.get("llm_api_key");
            String embKey = (String) aiConfig.get("embedding_api_key");
            log.info("[M3-3诊断] 发往Python mode={} model={} llm_model={} embedding_model={} "
                            + "llm_api_key尾4={} embedding_api_key尾4={} rerank_provider={} "
                            + "imagePaths={} attachmentPaths={}",
                    mode, model, aiConfig.get("llm_model"), aiConfig.get("embedding_model"),
                    llmKey != null ? "***" + llmKey.substring(Math.max(0, llmKey.length() - 4)) : "null",
                    embKey != null ? "***" + embKey.substring(Math.max(0, embKey.length() - 4)) : "null",
                    aiConfig.get("rerank_provider"),
                    imagePaths != null ? imagePaths.size() : 0,
                    attachmentPaths != null ? attachmentPaths.size() : 0);
        } else {
            log.warn("[M3-3诊断] 发往Python mode={} model={} ai_config=NULL，Python将走env兜底（易触发模型配置错误）",
                    mode, model);
        }

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
        log.info("[文档诊断] 发往Python processDocument docId={} fileType={} kbId={} tenantId={} aiConfigKeys={}",
                docId, fileType, kbId, tenantId, aiConfig == null ? "NULL" : aiConfig.keySet());

        return webClient.post()
                .uri("/ai/document/process")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(java.time.Duration.ofMinutes(10))
                .block();
    }

    /**
     * 提取文档按页分段文本（供前端预览真实翻页，M4-4 增强）。
     *
     * <p>纯本地解析，不依赖模型配置；PDF 用真实页码，docx/txt/md 用估算页码。
     * Python 服务不可用或解析失败时返回空列表，由调用方降级到已存全文估算。</p>
     *
     * @param filePath 文件绝对路径
     * @param fileType 文件类型（pdf / docx / md / txt）
     * @return 每页的 {page_no, text} 列表；失败返回空列表
     */
    public List<Map<String, Object>> extractPages(String filePath, String fileType) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("file_path", filePath);
        requestBody.put("file_type", fileType);
        try {
            Map<String, Object> resp = webClient.post()
                    .uri("/ai/document/extract-pages")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(java.time.Duration.ofMinutes(2))
                    .block();
            if (resp == null || !"ok".equals(resp.get("status"))) {
                log.warn("[文档诊断] Python extract-pages 失败 file={} reason={}",
                        filePath, resp == null ? "null" : resp.get("error"));
                return List.of();
            }
            Object pagesObj = resp.get("pages");
            if (pagesObj instanceof List<?> pages) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object p : pages) {
                    if (p instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pageMap = (Map<String, Object>) m;
                        result.add(pageMap);
                    }
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("[文档诊断] 调 Python extract-pages 异常 file={} : {}", filePath, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从向量库已存分块重建文档按页文本（预览兜底，不依赖原文件）。
     * 文档已向量化即可重建，解决原文件路径中文 / 文件被清理导致预览为空的问题。
     * 失败返回空列表，由调用方继续降级到已存全文。
     *
     * @param docId 文档 ID
     * @return 每页的 {page_no, text} 列表；失败返回空列表
     */
    public List<Map<String, Object>> getPagesFromDb(Long docId) {
        try {
            Map<String, Object> resp = webClient.post()
                    .uri("/ai/document/pages-from-db")
                    .bodyValue(Map.of("doc_id", String.valueOf(docId)))
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(java.time.Duration.ofMinutes(2))
                    .block();
            if (resp == null || !"ok".equals(resp.get("status"))) {
                log.warn("[文档诊断] Python pages-from-db 失败 docId={} reason={}",
                        docId, resp == null ? "null" : resp.get("error"));
                return List.of();
            }
            Object pagesObj = resp.get("pages");
            if (pagesObj instanceof List<?> pages) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object p : pages) {
                    if (p instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pageMap = (Map<String, Object>) m;
                        result.add(pageMap);
                    }
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("[文档诊断] 调 Python pages-from-db 异常 docId={} : {}", docId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除文档 — 调用 Python AI 服务清理该文档在向量库中的数据，并取消其可能正在排队
     * 的问答增强任务（对标业界成熟方案 任务取消）。Python 服务不可用时忽略，不阻塞删除主流程。
     */
    public void deleteDocument(Long docId) {
        try {
            webClient.delete()
                    .uri("/ai/document/{docId}", String.valueOf(docId))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("调 Python 删除文档向量失败 docId={} : {}", docId, e.getMessage());
        }
    }

    /**
     * 取消文档处理 — 调用 Python AI 服务清理该文档在向量库中的数据（若已写入），
     * 并标记其问答增强任务取消（对标业界成熟方案 任务取消）。与 {@link #deleteDocument} 区别：
     * 本方法不删除文档 DB 记录，仅清理向量与取消排队增强，配合 Java 侧将状态置为 cancelled。
     * Python 服务不可用时忽略，不阻塞取消主流程。
     */
    public void cancelDocument(Long docId) {
        try {
            webClient.post()
                    .uri("/ai/document/{docId}/cancel", String.valueOf(docId))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("调 Python 取消文档处理失败 docId={} : {}", docId, e.getMessage());
        }
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

    /**
     * 全局搜索 — 调用 Python AI 服务的 /ai/search/global 接口。
     * 返回 {documents: [...], messages: [...], total_documents, total_messages}，ES 不可用时两列表均为空。
     *
     * @param query          搜索关键词（支持运算符："""精确、-排除、+必含）
     * @param topK           每类返回条数
     * @param from           分页偏移
     * @param enableSemantic 是否启用向量语义召回
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> globalSearch(
            String query, String tenantId, String userId, List<Long> kbIds,
            int topK, int from, boolean enableSemantic
    ) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", query);
        requestBody.put("tenant_id", tenantId);
        requestBody.put("user_id", userId);
        requestBody.put("top_k", topK);
        requestBody.put("from_", from);
        requestBody.put("enable_semantic", enableSemantic);
        requestBody.put("kb_ids", kbIds != null ? kbIds.stream().map(String::valueOf).toList() : List.of());
        try {
            Map<String, Object> resp = webClient.post()
                    .uri("/ai/search/global")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(java.time.Duration.ofSeconds(15))
                    .block();
            if (resp == null) {
                return Map.of("documents", List.of(), "messages", List.of(), "total_documents", 0, "total_messages", 0);
            }
            return resp;
        } catch (Exception e) {
            log.warn("调 Python 全局搜索失败，返回空: {}", e.getMessage());
            return Map.of("documents", List.of(), "messages", List.of(), "total_documents", 0, "total_messages", 0);
        }
    }

    /**
     * 索引单条聊天消息到 ES — 保存消息后异步调用（失败不阻塞主流程）。
     */
    public void indexMessage(
            String messageId, String conversationId, String conversationTitle,
            String role, String content, String tenantId, String userId, String createTime
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message_id", messageId);
        body.put("conversation_id", conversationId);
        body.put("conversation_title", conversationTitle);
        body.put("role", role);
        body.put("content", content);
        body.put("tenant_id", tenantId);
        body.put("user_id", userId);
        body.put("create_time", createTime);
        try {
            webClient.post()
                    .uri("/ai/search/index-message")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("索引消息到 ES 失败，忽略: {}", e.getMessage());
        }
    }

    /**
     * 删除某会话的所有消息索引 — 删除会话时调用（失败不阻塞主流程）。
     */
    public void deleteConversationMessages(String conversationId, String tenantId) {
        try {
            webClient.delete()
                    .uri(uriBuilder -> uriBuilder
                            .path("/ai/search/messages/{conversationId}")
                            .queryParam("tenant_id", tenantId)
                            .build(conversationId))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("删除会话消息 ES 索引失败，忽略: {}", e.getMessage());
        }
    }
}
