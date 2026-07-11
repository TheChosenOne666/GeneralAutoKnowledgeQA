package com.xiongda.dto.config;

import lombok.Data;

public class AiConfigDto {

    @Data
    @lombok.Builder
    public static class AiConfigOut {
        private String llmProvider;
        private String llmModel;
        private String llmBaseUrl;
        private Double llmTemperature;
        private Integer llmMaxTokens;
        private String embeddingProvider;
        private String embeddingModel;
        private String embeddingBaseUrl;
        private Integer embeddingDimension;
        private String rerankProvider;
        private String rerankModel;
        private Boolean hasRerank;
    }

    @Data
    public static class AiConfigUpdate {
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
}
