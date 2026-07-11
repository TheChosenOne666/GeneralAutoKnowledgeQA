package com.xiongda.dto.member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

public class MemberDto {

    @Data
    @lombok.Builder
    public static class MemberOut {
        private UUID id;
        private String name;
        private String email;
        private String role;
        private Boolean isActive;
        private String avatarUrl;
        private String lastActiveAt;
        private String createdAt;
    }

    @Data
    public static class MemberUpdate {
        private String role;
        private Boolean isActive;
    }

    @Data
    public static class InviteRequest {
        @NotBlank @Email
        private String email;

        @NotBlank
        private String name;

        private String role = "member";
    }
}
