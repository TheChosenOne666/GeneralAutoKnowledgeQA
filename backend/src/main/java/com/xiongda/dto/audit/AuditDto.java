package com.xiongda.dto.audit;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

public class AuditDto {

    @Data
    @lombok.Builder
    public static class AuditLogOut {
        private UUID id;
        private String userEmail;
        private String action;
        private String resourceType;
        private String resourceId;
        private Map<String, Object> detail;
        private String ipAddress;
        private String createdAt;
    }
}
