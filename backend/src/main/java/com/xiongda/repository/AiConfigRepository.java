package com.xiongda.repository;

import com.xiongda.entity.AiConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiConfigRepository extends JpaRepository<AiConfig, UUID> {
    Optional<AiConfig> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    Optional<AiConfig> findByTenantIdAndUserIdIsNull(UUID tenantId);
}
