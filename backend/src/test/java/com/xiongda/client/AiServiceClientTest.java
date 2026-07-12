package com.xiongda.client;

import com.xiongda.model.entity.AiConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiServiceClient.toAiConfigMap 单元测试 — 验证 AI 配置转为 Python 所需 snake_case 字典。
 */
class AiServiceClientTest {

    @Test
    void toAiConfigMap_includesKeysAndSkipsNulls() {
        AiConfig cfg = new AiConfig();
        cfg.setLlmProvider("volcengine");
        cfg.setLlmModel("doubao-pro");
        cfg.setLlmApiKey("sk-xxx");
        cfg.setLlmBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        cfg.setEmbeddingModel("doubao-embedding");
        cfg.setEmbeddingApiKey("sk-embed");
        cfg.setEmbeddingDimension(1536);
        // rerank 留空

        Map<String, Object> m = AiServiceClient.toAiConfigMap(cfg);

        assertNull(m.get("rerank_model"));
        assertFalse(m.containsKey("rerank_model"));
        assertEquals("volcengine", m.get("llm_provider"));
        assertEquals("doubao-pro", m.get("llm_model"));
        assertEquals("sk-xxx", m.get("llm_api_key"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", m.get("llm_base_url"));
        assertEquals("doubao-embedding", m.get("embedding_model"));
        assertEquals("sk-embed", m.get("embedding_api_key"));
        assertEquals(1536, m.get("embedding_dimension"));
    }

    @Test
    void toAiConfigMap_nullCfg_returnsNull() {
        assertNull(AiServiceClient.toAiConfigMap(null));
    }
}
