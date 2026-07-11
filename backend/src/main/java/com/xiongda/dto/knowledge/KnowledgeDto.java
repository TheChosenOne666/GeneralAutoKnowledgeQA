package com.xiongda.dto.knowledge;

import lombok.Data;

import java.util.UUID;

public class KnowledgeDto {

    @Data
    @lombok.Builder
    public static class KnowledgeBaseOut {
        private UUID id;
        private String name;
        private String description;
        private String scope;
        private UUID ownerId;
        private Integer documentCount;
        private String createdAt;
    }

    @Data
    @lombok.Builder
    public static class DocumentOut {
        private UUID id;
        private UUID kbId;
        private String filename;
        private String fileType;
        private Long fileSize;
        private String status;
        private Integer chunkCount;
        private String errorMsg;
        private String createdAt;
    }

    @Data
    @lombok.Builder
    public static class UploadResponse {
        private UUID id;
        private String filename;
        private String status;
    }
}
