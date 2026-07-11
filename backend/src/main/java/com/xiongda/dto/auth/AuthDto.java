package com.xiongda.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public class AuthDto {

    @Data
    public static class LoginRequest {
        @NotBlank @Email
        private String email;

        @NotBlank @Size(min = 6, max = 128)
        private String password;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank @Size(max = 100)
        private String name;

        @NotBlank @Email
        private String email;

        @NotBlank @Size(min = 6, max = 128)
        private String password;
    }

    @Data
    @lombok.Builder
    public static class TokenResponse {
        private String accessToken;
        private String tokenType;
        private UserBrief user;
    }

    @Data
    @lombok.Builder
    public static class UserBrief {
        private String id;
        private String name;
        private String email;
        private String role;
        private String tenantId;
        private String avatarUrl;
    }
}
