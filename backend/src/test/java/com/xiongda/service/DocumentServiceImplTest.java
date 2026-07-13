package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.xiongda.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
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

    private DocumentServiceImpl documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentServiceImpl();
        ReflectionTestUtils.setField(documentService, "baseMapper", documentMapper);
        ReflectionTestUtils.setField(documentService, "knowledgeBaseService", knowledgeBaseService);
        ReflectionTestUtils.setField(documentService, "aiServiceClient", aiServiceClient);
        ReflectionTestUtils.setField(documentService, "tenantMapper", tenantMapper);
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
    void uploadDocument_sharedAsMember_denied() {
        when(knowledgeBaseService.getById(1L)).thenReturn(kb(1L, "shared", 999L, 10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(1L, 10L, user(100L, UserConstant.DEFAULT_ROLE),
                        "test.pdf", "pdf", 1024L, "/uploads/test.pdf"));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void uploadDocument_tenantAdminCrossTenant_denied() {
        // 租户 10 的 tenant_admin 不能写租户 99 的共享库（对齐 WeKnora own-KB 判定）
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
        Document doc2 = buildDoc(2L, "doc2.txt", "txt", "pending");
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

    // ==================== 配额拦截（M3-5，对齐 WeKnora） ====================

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
}
