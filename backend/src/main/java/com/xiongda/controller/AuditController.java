package com.xiongda.controller;

import com.xiongda.dto.audit.AuditDto.*;
import com.xiongda.repository.AuditLogRepository;
import com.xiongda.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审计日志控制器 — 租户管理员查本租户，平台超管查全局。
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping
    public ResponseEntity<List<AuditLogOut>> listLogs(
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        var role = securityContextUtil.getCurrentRole();
        var tenantId = securityContextUtil.getCurrentTenantId();
        var pageable = PageRequest.of(offset / limit, limit);

        var page = switch (role) {
            case "super_admin" -> auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
            default -> {
                if (action != null) {
                    yield auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenantId, action, pageable);
                }
                yield auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
            }
        };

        var result = page.getContent().stream().map(log -> AuditLogOut.builder()
                .id(log.getId())
                .userEmail(log.getUserEmail())
                .action(log.getAction())
                .resourceType(log.getResourceType())
                .resourceId(log.getResourceId())
                .detail(log.getDetail())
                .ipAddress(log.getIpAddress())
                .createdAt(log.getCreatedAt().toString())
                .build()
        ).toList();

        return ResponseEntity.ok(result);
    }
}
