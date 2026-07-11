package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.mapper.DocumentMapper;
import com.xiongda.model.entity.Document;
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
 * 文档服务实现单元测试 — 覆盖上传、列表、删除、VO 转换。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private DocumentMapper documentMapper;

    private DocumentServiceImpl documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentServiceImpl();
        ReflectionTestUtils.setField(documentService, "baseMapper", documentMapper);
    }

    // ==================== 上传文档 ====================

    @Test
    void uploadDocument_success() {
        doAnswer(inv -> {
            inv.getArgument(0, Document.class).setId(200L);
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        Long docId = documentService.uploadDocument(1L, 10L, 100L, "test.pdf", "pdf", 1024L, "/uploads/test.pdf");
        assertEquals(200L, docId);

        verify(documentMapper).insert(any(Document.class));
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
    void deleteDocument_success() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "ready");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.deleteById(1L)).thenReturn(1);

        boolean result = documentService.deleteDocument(1L, 10L);
        assertTrue(result);
        verify(documentMapper).deleteById(1L);
    }

    @Test
    void deleteDocument_notFound() {
        when(documentMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocument(999L, 10L));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
    }

    @Test
    void deleteDocument_crossTenant() {
        Document doc = buildDoc(1L, "test.pdf", "pdf", "ready");
        doc.setTenantId(99L);
        when(documentMapper.selectById(1L)).thenReturn(doc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.deleteDocument(1L, 10L));
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
}
