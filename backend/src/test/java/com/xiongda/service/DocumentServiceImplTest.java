package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xiongda.common.ErrorCode;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.BusinessException;
import com.xiongda.client.AiServiceClient;
import com.xiongda.mapper.DocumentMapper;
import com.xiongda.mapper.TenantMapper;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.DocumentVO;
import com.xiongda.service.AiConfigService;
import com.xiongda.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 文档服务实现单元测试 — 覆盖上传、列表、删除、VO 转换及 RBAC 写权限。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private AiConfigService aiConfigService;

    private DocumentServiceImpl documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentServiceImpl();
        ReflectionTestUtils.setField(documentService, "baseMapper", documentMapper);
        ReflectionTestUtils.setField(documentService, "knowledgeBaseService", knowledgeBaseService);
        ReflectionTestUtils.setField(documentService, "aiServiceClient", aiServiceClient);
        ReflectionTestUtils.setField(documentService, "tenantMapper", tenantMapper);
        ReflectionTestUtils.setField(documentService, "aiConfigService", aiConfigService);
    }

    private User user(Long id, String role) {
        User u = new User();
        u.setId(id);
        u.setTenantId(10L);
        u.setRole(role);
        return u;
    }

    private KnowledgeBase kb(Long id, String scope, Long ownerId, Long tenantId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setScope(scope);
        kb.setOwnerId(ownerId);
        kb.setTenantId(tenantId);
        return kb;
    }

    // ==================== 上传文档 ====================

    @Test
    void uploadDocument_personalOwner_success() {
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));
        doAnswer(inv -> {
            inv.getArgument(0, Document.class).setId(200L);
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        Long docId = documentService.uploadDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE),
                "test.pdf", "pdf", 1024L, "/uploads/test.pdf");
        assertEquals(200L, docId);
        verify(documentMapper).insert(any(Document.class));
    }

    @Test
    void uploadDocument_personalNotOwner_denied() {
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 999L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE),
                        "test.pdf", "pdf", 1024L, "/uploads/test.pdf"));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void uploadDocument_sharedAsTenantAdmin_success() {
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "shared", 999L, 10L));
        doAnswer(inv -> {
            inv.getArgument(0, Document.class).setId(201L);
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        Long docId = documentService.uploadDocument(1L, 10L, user(100L, UserConstant.TENANT_ADMIN_ROLE),
                "test.pdf", "pdf", 1024L, "/uploads/test.pdf");
        assertEquals(201L, docId);
    }

    @Test
    void uploadDocument_initialStatus_processing() {
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));
        Document[] captured = new Document[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0, Document.class);
            captured[0].setId(202L);
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        documentService.uploadDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE),
                "test.pdf", "pdf", 1024L, "/uploads/test.pdf");
        assertEquals("processing", captured[0].getStatus());
    }

    @Test
    void uploadDocument_sharedAsMember_denied() {
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "shared", 999L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE),
                        "test.pdf", "pdf", 1024L, "/uploads/test.pdf"));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void uploadDocument_tenantAdminCrossTenant_denied() {
        // 租户 10 的 tenant_admin 不能写租户 99 的共享库（对齐 业界 own-KB（自有 KB）判定）
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "shared", 999L, 99L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(1L, 10L, user(100L, UserConstant.TENANT_ADMIN_ROLE),
                        "test.pdf", "pdf", 1024L, "/uploads/test.pdf"));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ==================== 列表查询 ====================

    @Test
    void listDocuments_success() {
        Document doc1 = buildDoc(1L, "doc1.pdf", "pdf", "ready");
        Document doc2 = buildDoc(2L, "doc2.txt", "txt", "processing");
        when(documentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(doc1, doc2));

        List<DocumentVO> result = documentService.listDocuments(1L, 10L);
        assertEquals(2, result.size());
        assertEquals("doc1.pdf", result.get(0).getFilename());
    }

    @Test
    void listDocuments_empty() {
        when(documentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        List<DocumentVO> result = documentService.listDocuments(1L, 10L);
        assertTrue(result.isEmpty());
    }

    // ==================== 删除文档 ====================

    @Test
    void deleteDocument_personalOwner_success() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "ready");
        doc.setKbId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));
        when(documentMapper.deleteById(1L)).thenReturn(1);

        boolean result = documentService.deleteDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE));
        assertTrue(result);
        verify(documentMapper).deleteById(1L);
        // 删除文档时同步清理 Python 侧向量并取消增强任务（对标业界成熟方案 任务取消）
        verify(aiServiceClient).deleteDocument(1L);
    }

    @Test
    void deleteDocument_sharedAsTenantAdmin_success() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "ready");
        doc.setKbId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "shared", 999L, 10L));
        when(documentMapper.deleteById(1L)).thenReturn(1);

        boolean result = documentService.deleteDocument(1L, 10L, user(100L, UserConstant.TENANT_ADMIN_ROLE));
        assertTrue(result);
        verify(aiServiceClient).deleteDocument(1L);
    }

    @Test
    void deleteDocument_personalNotOwner_denied() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "ready");
        doc.setKbId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 999L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void deleteDocument_notFound() {
        when(documentMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocument(999L, 10L, user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
    }

    @Test
    void deleteDocument_crossTenant() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "ready");
        doc.setTenantId(99L);
        when(documentMapper.selectById(1L)).thenReturn(doc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ==================== 批量删除文档 ====================

    @Test
    void deleteDocuments_allSuccess_onceCacheInvalidate() {
        Document d1 = buildDoc(1L, "a.pdf", "pdf", "ready");
        Document d2 = buildDoc(2L, "b.pdf", "pdf", "ready");
        when(documentMapper.selectById(1L)).thenReturn(d1);
        when(documentMapper.selectById(2L)).thenReturn(d2);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));
        when(documentMapper.deleteById(1L)).thenReturn(1);
        when(documentMapper.deleteById(2L)).thenReturn(1);

        int deleted = documentService.deleteDocuments(List.of(1L, 2L), 10L,
                user(100L, UserConstant.DEFAULT_ROLE));
        assertEquals(2, deleted);
        verify(aiServiceClient).deleteDocument(1L);
        verify(aiServiceClient).deleteDocument(2L);
        // 批量删除只清一次 L1 缓存
        verify(aiServiceClient, org.mockito.Mockito.times(1)).invalidateCache(10L);
    }

    @Test
    void deleteDocuments_dedup() {
        Document d1 = buildDoc(1L, "a.pdf", "pdf", "ready");
        when(documentMapper.selectById(1L)).thenReturn(d1);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));
        when(documentMapper.deleteById(1L)).thenReturn(1);

        int deleted = documentService.deleteDocuments(List.of(1L, 1L), 10L,
                user(100L, UserConstant.DEFAULT_ROLE));
        assertEquals(1, deleted);
        verify(documentMapper, org.mockito.Mockito.times(1)).deleteById(1L);
    }

    @Test
    void deleteDocuments_failFast_notFound_deletesNothing() {
        Document d1 = buildDoc(1L, "a.pdf", "pdf", "ready");
        when(documentMapper.selectById(1L)).thenReturn(d1);
        when(documentMapper.selectById(2L)).thenReturn(null);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocuments(List.of(1L, 2L), 10L,
                        user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
        // fail-fast：校验阶段抛异常，不删除任何文档、不清缓存
        verify(documentMapper, never()).deleteById(any());
        verify(aiServiceClient, never()).invalidateCache(any());
    }

    @Test
    void deleteDocuments_failFast_noAuth_deletesNothing() {
        Document d1 = buildDoc(1L, "a.pdf", "pdf", "ready");
        // 第一个文档即无写权限（kb owner=999 ≠ 当前用户 100），fail-fast 立即抛异常，
        // 后续文档不会进入校验，因此无需 stub selectById(2L)。
        when(documentMapper.selectById(1L)).thenReturn(d1);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 999L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocuments(List.of(1L, 2L), 10L,
                        user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
        verify(documentMapper, never()).deleteById(any());
    }

    @Test
    void deleteDocuments_emptyList() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocuments(List.of(), 10L,
                        user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    // ==================== VO 转换 ====================

    @Test
    void getDocumentVO_null() {
        assertNull(documentService.getDocumentVO(null));
    }

    @Test
    void getDocumentVO_success() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "ready");
        doc.setFileSize(2048L);
        doc.setChunkCount(10);

        DocumentVO vo = documentService.getDocumentVO(doc);
        assertEquals(1L, vo.getId());
        assertEquals("test.pdf", vo.getFilename());
        assertEquals("pdf", vo.getFileType());
        assertEquals("ready", vo.getStatus());
        assertEquals(10, vo.getChunkCount());
    }

    @Test
    void updateDocumentStatus_transitionsStage() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "parsing");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);

        boolean ok = documentService.updateDocumentStatus(1L, "retrieving", 5, null, null);
        assertTrue(ok);
        assertEquals("retrieving", doc.getStatus());
        assertEquals(5, doc.getChunkCount());
    }

    // ==================== 异步增强（对齐 业界 finalizing（异步增强）=queryable） ====================

    @Test
    void triggerDocumentProcessing_optimizingTreatedAsSuccess() {
        // M5-1 异步化：Python 仅入队即返回 processing，Java 视为成功（不阻塞），
        // 后续 retrieving/optimizing/ready 由 worker 经状态回调推进；此处校验
        // ① Java 不被误判为 failed、② 入队成功分支清 L1 缓存（保证新文档立即可搜）。
        Document doc = buildDoc(1L, "t.pdf", "pdf", "processing");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);
        when(aiConfigService.getRawConfig(10L, 100L)).thenReturn(null);
        when(aiServiceClient.processDocument(eq(1L), any(), any(), eq(1L), eq(10L), any()))
                .thenReturn(Map.of("status", "processing"));

        documentService.triggerDocumentProcessing(1L, "/p", "txt", 1L, 10L, 100L);

        // 异步 lambda：至少置过一次 parsing（入队前置），且最终不应出现 failed
        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper, timeout(3000).atLeast(1)).updateById(cap.capture());
        List<Document> all = cap.getAllValues();
        assertEquals("parsing", all.get(0).getStatus());
        assertTrue(all.stream().noneMatch(d -> "failed".equals(d.getStatus())),
                "返回 processing 不应被标记为 failed");
        // 入队成功分支清 L1 缓存（failed 分支不会调用）
        verify(aiServiceClient, timeout(3000)).invalidateCache(10L);
    }

    @Test
    void updateDocumentStatus_terminalGuard_ignoresStaleStage() {
        // 终态 ready 已落定，迟到的最终之前阶段回调（optimizing）应被忽略
        Document doc = buildDoc(1L, "t.pdf", "pdf", "ready");
        when(documentMapper.selectById(1L)).thenReturn(doc);

        boolean ok = documentService.updateDocumentStatus(1L, "optimizing", null, null, null);
        assertFalse(ok);
        assertEquals("ready", doc.getStatus());
    }

    @Test
    void updateDocumentStatus_successClearsStaleError() {
        // 成功终态（ready）应清空残留的 error_msg / model_config_error
        Document doc = buildDoc(1L, "t.pdf", "pdf", "failed");
        doc.setErrorMsg("boom");
        doc.setModelConfigError(true);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);

        boolean ok = documentService.updateDocumentStatus(1L, "ready", 5, null, null);
        assertTrue(ok);
        assertNull(doc.getErrorMsg());
        assertFalse(doc.getModelConfigError());
    }

    @Test
    void updateDocumentStatus_optimizingToReady_allowed() {
        // 中间态 optimizing → 终态 ready 应允许（后台增强完成后推进）
        Document doc = buildDoc(1L, "t.pdf", "pdf", "optimizing");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);

        boolean ok = documentService.updateDocumentStatus(1L, "ready", 5, null, null);
        assertTrue(ok);
        assertEquals("ready", doc.getStatus());
    }

    // ==================== 取消文档处理（M5 软取消） ====================

    @Test
    void cancelDocument_nonTerminal_success() {
        // 非终态（processing）→ 标 cancelled + 通知 Python 清向量 + 取消增强 + 清 L1 缓存
        Document doc = buildDoc(1L, "t.pdf", "pdf", "processing");
        doc.setKbId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);

        boolean result = documentService.cancelDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE));
        assertTrue(result);

        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).updateById(cap.capture());
        assertEquals("cancelled", cap.getValue().getStatus());
        // 通知 Python 清理向量 + 取消增强
        verify(aiServiceClient).cancelDocument(1L);
        verify(aiServiceClient).invalidateCache(10L);
    }

    @Test
    void cancelDocument_terminalIdempotent() {
        // 终态（ready）幂等返回，不重复通知 Python
        Document doc = buildDoc(1L, "t.pdf", "pdf", "ready");
        doc.setKbId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));

        boolean result = documentService.cancelDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE));
        assertTrue(result);
        verify(aiServiceClient, never()).cancelDocument(1L);
    }

    @Test
    void updateDocumentStatus_cancelledTerminalGuard_ignoresStaleStage() {
        // 终态 cancelled 已落定，迟到的最终之前阶段回调（optimizing）应被忽略
        Document doc = buildDoc(1L, "t.pdf", "pdf", "cancelled");
        when(documentMapper.selectById(1L)).thenReturn(doc);

        boolean ok = documentService.updateDocumentStatus(1L, "optimizing", null, null, null);
        assertFalse(ok);
        assertEquals("cancelled", doc.getStatus());
    }

    @Test
    void triggerDocumentProcessing_raceCancelledCleanupVectors() {
        // M5-1 异步化：向量清理竞态（用户中途取消）已由 Python worker 的取消检查点处理
        // （process() 在入库后、notify optimizing 前检测到 cancelled 即清理向量并回调 cancelled），
        // Java 侧 triggerDocumentProcessing 仅负责入队、不再承担向量清理；
        // 此处校验：Python 返回 processing（已入队）时，Java 不误判 failed、也不重复调删除。
        when(documentMapper.selectById(1L))
                .thenReturn(buildDoc(1L, "t.pdf", "pdf", "processing"));
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);
        when(aiConfigService.getRawConfig(10L, 100L)).thenReturn(null);
        when(aiServiceClient.processDocument(eq(1L), any(), any(), eq(1L), eq(10L), any()))
                .thenReturn(Map.of("status", "processing"));

        documentService.triggerDocumentProcessing(1L, "/p", "txt", 1L, 10L, 100L);

        // 返回 processing：不调 Python deleteDocument（清理已移交 worker），也不把状态置为 failed
        verify(aiServiceClient, never()).deleteDocument(1L);
        verify(aiServiceClient, timeout(3000)).invalidateCache(10L);
        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper, timeout(3000).atLeast(1)).updateById(cap.capture());
        assertTrue(cap.getAllValues().stream().noneMatch(d -> "failed".equals(d.getStatus())),
                "返回 processing 不应被标记为 failed");
    }

    @Test
    void triggerDocumentProcessing_runsOnCustomThreadPool() {
        // 验证文档处理异步任务提交到自定义线程池（线程名前缀 doc-process-），
        // 而非 CompletableFuture.runAsync 默认的 ForkJoinPool.commonPool()。
        String[] execThread = {null};
        when(aiConfigService.getRawConfig(10L, 100L)).thenReturn(null);
        doAnswer(invocation -> {
            execThread[0] = Thread.currentThread().getName();
            return Map.of("status", "processing");
        }).when(aiServiceClient).processDocument(eq(1L), any(), any(), eq(1L), eq(10L), any());
        when(documentMapper.selectById(1L)).thenReturn(buildDoc(1L, "t.pdf", "pdf", "processing"));
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);

        documentService.triggerDocumentProcessing(1L, "/p", "txt", 1L, 10L, 100L);

        verify(aiServiceClient, timeout(3000)).processDocument(eq(1L), any(), any(), eq(1L), eq(10L), any());
        assertNotNull(execThread[0], "processDocument 应被异步线程执行");
        assertTrue(execThread[0].startsWith("doc-process-"),
                "应运行在自定义线程池线程，实际线程=" + execThread[0]);
    }

    // ==================== 重试文档处理（M5 易用性增强） ====================

    @Test
    void retryDocument_failed_resetAndReTrigger() throws IOException {
        // 创建真实临时文件，确保原文件存在（重试前置校验通过）
        Path tmp = Files.createTempFile("retry", ".pdf");
        // 每次 getById 返回全新副本：重置调用的实例不会被异步触发线程改写，
        // 以便断言「重置为 processing + 清空错误标记 / 分块」这一中间态。
        when(documentMapper.selectById(1L)).thenAnswer(inv -> {
            Document d = buildDoc(1L, "t.pdf", "pdf", "failed");
            d.setKbId(1L);
            d.setFilePath(tmp.toString());
            d.setModelConfigError(true);
            d.setErrorMsg("boom");
            d.setChunkCount(3);
            return d;
        });
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);
        when(aiConfigService.getRawConfig(10L, 100L)).thenReturn(null);
        when(aiServiceClient.processDocument(eq(1L), any(), any(), eq(1L), eq(10L), any()))
                .thenReturn(Map.of("status", "ready", "chunk_count", 5, "content", "c"));

        boolean result = documentService.retryDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE));
        assertTrue(result);

        // 第一次 updateById 即重置：status=processing + 清空错误标记 / 分块
        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper, org.mockito.Mockito.atLeast(1)).updateById(cap.capture());
        Document reset = cap.getAllValues().get(0);
        assertEquals("processing", reset.getStatus());
        assertFalse(reset.getModelConfigError());
        assertNull(reset.getErrorMsg());
        assertEquals(0, reset.getChunkCount());
        // 复用原上传者配置重新触发处理（processDocument 被异步调用）
        verify(aiServiceClient, timeout(3000)).processDocument(eq(1L), any(), any(), eq(1L), eq(10L), any());
    }

    @Test
    void retryDocument_notFound() {
        when(documentMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.retryDocument(999L, 10L, user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
    }

    @Test
    void retryDocument_readyRejected() {
        // 已就绪（非 failed/cancelled）不可重试
        Document doc = buildDoc(1L, "t.pdf", "pdf", "ready");
        doc.setKbId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.retryDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    void retryDocument_fileMissing_rejected() {
        // 原文件已不存在，重试应被拒并提示重新上传
        Document doc = buildDoc(1L, "gone.pdf", "pdf", "failed");
        doc.setKbId(1L);
        doc.setFilePath("/uploads/does-not-exist-xyz.pdf");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 100L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.retryDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
        verify(documentMapper, never()).updateById(any(Document.class));
        verify(aiServiceClient, never()).processDocument(any(), any(), any(), any(), any(), any());
    }

    @Test
    void retryDocument_personalNotOwner_denied() {
        Document doc = buildDoc(1L, "t.pdf", "pdf", "failed");
        doc.setKbId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "personal", 999L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.retryDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ==================== 辅助方法 ====================

    private Document buildDoc(Long id, String filename, String fileType, String status) {
        Document doc = new Document();
        doc.setId(id);
        doc.setKbId(1L);
        doc.setTenantId(10L);
        doc.setFilename(filename);
        doc.setFileType(fileType);
        doc.setFileSize(1024L);
        doc.setFilePath("/uploads/" + filename);
        doc.setStatus(status);
        doc.setChunkCount(0);
        doc.setUploadedBy(100L);
        doc.setCreateTime(new Date());
        return doc;
    }

    // ==================== 配额拦截（M3-5，对标业界成熟方案） ====================

    @Test
    void uploadDocument_quotaExceeded() {
        KnowledgeBase kb = kb(1L, "shared", 1L, 10L);
        when(knowledgeBaseService.getById(1L)).thenReturn(kb);
        // 租户 maxDocuments=1，已有 1 篇文档
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setMaxDocuments(1);
        when(tenantMapper.selectById(10L)).thenReturn(tenant);
        when(documentMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        User u = user(1L, UserConstant.TENANT_ADMIN_ROLE);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(1L, 10L, u, "f.pdf", "pdf", 10L, "/p"));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    // ==================== 清除失败文档旧归因标记（M3-3 修复） ====================

    @Test
    void clearFailedConfigErrorFlags_callsMapperWithWrapper() {
        ArgumentCaptor<UpdateWrapper<Document>> cap = ArgumentCaptor.forClass(UpdateWrapper.class);
        when(documentMapper.update(any(), any())).thenReturn(2);

        int n = documentService.clearFailedConfigErrorFlags(7L);

        assertEquals(2, n);
        verify(documentMapper).update(any(), cap.capture());
        assertNotNull(cap.getValue());
    }

    @Test
    void clearFailedConfigErrorFlags_nullTenantClearsGlobally() {
        when(documentMapper.update(any(), any())).thenReturn(0);

        int n = documentService.clearFailedConfigErrorFlags(null);

        assertEquals(0, n);
        verify(documentMapper).update(any(), any());
    }
}
