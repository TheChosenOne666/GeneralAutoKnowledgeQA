package com.xiongda.repository;

import com.xiongda.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    List<Document> findByKbIdAndTenantIdOrderByCreatedAtDesc(UUID kbId, UUID tenantId);
}
