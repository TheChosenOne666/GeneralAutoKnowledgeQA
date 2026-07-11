package com.xiongda.controller;

import com.xiongda.dto.knowledge.KnowledgeDto.*;
import com.xiongda.entity.Document;
import com.xiongda.entity.KnowledgeBase;
import com.xiongda.repository.DocumentRepository;
import com.xiongda.repository.KnowledgeBaseRepository;
import com.xiongda.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 知识库控制器。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping("/bases")
    public ResponseEntity<List<KnowledgeBaseOut>> listBases(@RequestParam(required = false) String scope) {
        var tenantId = securityContextUtil.getCurrentTenantId();
        List<KnowledgeBase> kbs;

        if (scope != null) {
            if ("personal".equals(scope)) {
                kbs = kbRepository.findByTenantIdAndScopeAndOwnerId(tenantId, "personal", securityContextUtil.getCurrentUserId());
            } else {
                kbs = kbRepository.findByTenantIdAndScope(tenantId, "shared");
            }
        } else {
            kbs = kbRepository.findByTenantId(tenantId);
        }

        var result = kbs.stream().map(kb -> KnowledgeBaseOut.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .scope(kb.getScope())
                .ownerId(kb.getOwnerId())
                .documentCount(kb.getDocumentCount())
                .createdAt(kb.getCreatedAt().toString())
                .build()
        ).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/bases/{kbId}/documents")
    public ResponseEntity<List<DocumentOut>> listDocuments(@PathVariable UUID kbId) {
        var tenantId = securityContextUtil.getCurrentTenantId();
        var docs = documentRepository.findByKbIdAndTenantIdOrderByCreatedAtDesc(kbId, tenantId);
        var result = docs.stream().map(doc -> DocumentOut.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .filename(doc.getFilename())
                .fileType(doc.getFileType())
                .fileSize(doc.getFileSize())
                .status(doc.getStatus())
                .chunkCount(doc.getChunkCount())
                .errorMsg(doc.getErrorMsg())
                .createdAt(doc.getCreatedAt().toString())
                .build()
        ).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/bases/{kbId}/documents")
    public ResponseEntity<UploadResponse> uploadDocument(
            @PathVariable UUID kbId,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        var tenantId = securityContextUtil.getCurrentTenantId();
        var userId = securityContextUtil.getCurrentUserId();

        // 保存文件
        var uploadDir = Path.of("uploads", tenantId.toString());
        Files.createDirectories(uploadDir);
        var filePath = uploadDir.resolve(UUID.randomUUID() + "_" + file.getOriginalFilename());
        file.transferTo(filePath.toFile());

        var doc = Document.builder()
                .kbId(kbId)
                .tenantId(tenantId)
                .filename(file.getOriginalFilename())
                .fileType(getExtension(file.getOriginalFilename()))
                .fileSize(file.getSize())
                .filePath(filePath.toString())
                .status("pending")
                .uploadedBy(userId)
                .build();
        documentRepository.save(doc);

        // TODO: 异步调用 AiServiceClient.processDocument 触发文档处理

        return ResponseEntity.ok(UploadResponse.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .status(doc.getStatus())
                .build()
        );
    }

    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID docId) {
        documentRepository.deleteById(docId);
        // TODO: 删除向量数据库中的数据
        return ResponseEntity.noContent().build();
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "unknown";
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
