package com.xiongda.controller;

import com.xiongda.common.BaseResponse;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.PageContentVO;
import com.xiongda.service.DocumentService;
import com.xiongda.service.KnowledgeBaseService;
import com.xiongda.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseController 文件预览相关接口单元测试。
 *
 * <p>覆盖：原文件被删 / 被改 / 无权限 / 读取失败 时均返回友好状态或业务异常，
 * 避免 IOException 穿透到容器渲染 Whitelabel 错误页。</p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private DocumentService documentService;

    @Mock
    private UserService userService;

    private KnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeBaseController();
        ReflectionTestUtils.setField(controller, "knowledgeBaseService", knowledgeBaseService);
        ReflectionTestUtils.setField(controller, "documentService", documentService);
        ReflectionTestUtils.setField(controller, "userService", userService);
    }

    private User loginUser() {
        User user = new User();
        user.setId(7L);
        user.setTenantId(3L);
        return user;
    }

    private Document docWithFile(Path filePath, long size) {
        Document doc = new Document();
        doc.setId(1L);
        doc.setTenantId(3L);
        doc.setFilename("测试.pdf");
        doc.setFileType("pdf");
        doc.setFilePath(filePath.toString());
        doc.setFileSize(size);
        return doc;
    }

    @Test
    void checkDocumentFileStatus_fileExists_returnsOk() throws Exception {
        Path file = Files.createTempFile("kb", ".pdf");
        Files.writeString(file, "hello");
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        when(documentService.getById(1L)).thenReturn(docWithFile(file, Files.size(file)));

        BaseResponse<Map<String, Object>> resp = controller.checkDocumentFileStatus(1L, null);
        assertEquals(0, resp.getCode());
        assertTrue((Boolean) resp.getData().get("exists"));
        assertFalse((Boolean) resp.getData().get("changed"));
        Files.deleteIfExists(file);
    }

    @Test
    void checkDocumentFileStatus_fileDeleted_returnsMissing() {
        Document doc = docWithFile(Path.of("non-existent-path", "gone.pdf"), 100L);
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        when(documentService.getById(1L)).thenReturn(doc);

        BaseResponse<Map<String, Object>> resp = controller.checkDocumentFileStatus(1L, null);
        assertEquals(0, resp.getCode());
        assertFalse((Boolean) resp.getData().get("exists"));
        assertEquals("原文件已被清理或删除，无法预览，请重新上传该文档", resp.getData().get("message"));
    }

    @Test
    void checkDocumentFileStatus_fileChanged_returnsChanged() throws Exception {
        Path file = Files.createTempFile("kb", ".pdf");
        Files.writeString(file, "changed content longer");
        // 记录的上传大小为旧值，与当前不一致 → 判定为已修改
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        when(documentService.getById(1L)).thenReturn(docWithFile(file, 1L));

        BaseResponse<Map<String, Object>> resp = controller.checkDocumentFileStatus(1L, null);
        assertEquals(0, resp.getCode());
        assertTrue((Boolean) resp.getData().get("exists"));
        assertTrue((Boolean) resp.getData().get("changed"));
        assertEquals("检测发现原文件已被修改，预览内容可能与知识库索引不一致，建议重新上传该文档",
                resp.getData().get("message"));
        Files.deleteIfExists(file);
    }

    @Test
    void checkDocumentFileStatus_docNotFound_throws() {
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        when(documentService.getById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.checkDocumentFileStatus(99L, null));
        assertEquals("文档不存在", ex.getMessage());
    }

    @Test
    void checkDocumentFileStatus_noAuth_throws() {
        User other = new User();
        other.setId(2L);
        other.setTenantId(9L);
        Document doc = docWithFile(Path.of("x.pdf"), 1L);
        when(userService.getLoginUser(any())).thenReturn(other);
        when(documentService.getById(1L)).thenReturn(doc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.checkDocumentFileStatus(1L, null));
        assertEquals("无权限查看该文档", ex.getMessage());
    }

    @Test
    void getDocumentFile_missingFile_throwsFriendlyError() {
        Document doc = docWithFile(Path.of("non-existent-path", "gone.pdf"), 100L);
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        when(documentService.getById(1L)).thenReturn(doc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getDocumentFile(1L, null));
        assertTrue(ex.getMessage().contains("无法预览"));
        // 不泄露 IOException / Whitelabel
    }

    @Test
    void getDocumentFile_presentFile_returnsPdfStream() throws Exception {
        Path file = Files.createTempFile("kb", ".pdf");
        Files.writeString(file, "%PDF-1.4 fake");
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        when(documentService.getById(1L)).thenReturn(docWithFile(file, Files.size(file)));

        ResponseEntity<?> resp = controller.getDocumentFile(1L, null);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertEquals("application/pdf", resp.getHeaders().getContentType().toString());
        // 关闭流以释放 Windows 文件锁，便于清理临时文件
        ((org.springframework.core.io.InputStreamResource) resp.getBody()).getInputStream().close();
        Files.deleteIfExists(file);
    }

    @Test
    void getDocumentPages_returnsPages() {
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        PageContentVO p1 = new PageContentVO();
        p1.setPageNo(1);
        p1.setText("第一页内容");
        PageContentVO p2 = new PageContentVO();
        p2.setPageNo(2);
        p2.setText("第二页内容");
        when(documentService.getDocumentPages(eq(1L), eq(3L), any())).thenReturn(List.of(p1, p2));

        BaseResponse<List<PageContentVO>> resp = controller.getDocumentPages(1L, null);
        assertEquals(0, resp.getCode());
        assertEquals(2, resp.getData().size());
        assertEquals(1, resp.getData().get(0).getPageNo());
        assertEquals("第一页内容", resp.getData().get(0).getText());
        assertEquals(2, resp.getData().get(1).getPageNo());
    }

    @Test
    void getDocumentPages_noPermission_throws() {
        // 权限校验在 service 层（与 getDocumentContent 同款 ThrowUtils），controller 透传异常
        User other = new User();
        other.setId(2L);
        other.setTenantId(9L);
        when(userService.getLoginUser(any())).thenReturn(other);
        when(documentService.getDocumentPages(eq(1L), eq(9L), any()))
                .thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限查看该文档"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getDocumentPages(1L, null));
        assertEquals("无权限查看该文档", ex.getMessage());
    }
}
