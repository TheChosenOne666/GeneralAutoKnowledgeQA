package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 配置视图对象（不含 API Key 明文）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class AiConfigVO implements Serializable {

    // LLM
    private String llmProvider;
    private String llmModel;
    private String llmBaseUrl;
    private Double llmTemperature;
    private Integer llmMaxTokens;

    // Embedding
    private String embeddingProvider;
    private String embeddingModel;
    private String embeddingBaseUrl;
    private Integer embeddingDimension;

    // Rerank
    private String rerankProvider;
    private String rerankModel;

    /**
     * 是否配置了 Rerank
     */
    private Boolean hasRerank;
}
