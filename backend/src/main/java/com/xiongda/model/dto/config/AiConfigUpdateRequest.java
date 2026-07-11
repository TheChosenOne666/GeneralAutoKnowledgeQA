package com.xiongda.model.dto.config;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 配置更新请求。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class AiConfigUpdateRequest implements Serializable {

    private String llmProvider;
    private String llmModel;
    private String llmApiKey;
    private String llmBaseUrl;
    private Double llmTemperature;
    private Integer llmMaxTokens;

    private String embeddingProvider;
    private String embeddingModel;
    private String embeddingApiKey;
    private String embeddingBaseUrl;
    private Integer embeddingDimension;

    private String rerankProvider;
    private String rerankModel;
    private String rerankApiKey;
}
