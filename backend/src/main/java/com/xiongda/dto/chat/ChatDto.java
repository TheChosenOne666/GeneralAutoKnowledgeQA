package com.xiongda.dto.chat;

import lombok.Data;

import java.util.List;
import java.util.UUID;

public class ChatDto {

    @Data
    public static class ChatRequest {
        private UUID conversationId;
        private String content;
        private List<UUID> kbIds;
        private String model;
        private String mode = "rag"; // rag / search
    }

    @Data
    @lombok.Builder
    public static class ConversationOut {
        private UUID id;
        private String title;
        private Integer messageCount;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    @lombok.Builder
    public static class MessageOut {
        private UUID id;
        private String role;
        private String content;
        private List<Object> sources;
        private String model;
        private String createdAt;
    }
}
