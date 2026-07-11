package com.xiongda.repository;

import com.xiongda.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    List<KnowledgeBase> findByTenantIdAndScope(UUID tenantId, String scope);

    List<KnowledgeBase> findByTenantIdAndScopeAndOwnerId(UUID tenantId, String scope, UUID ownerId);

    List<KnowledgeBase> findByTenantId(UUID tenantId);
}
