package com.xiongda.controller;

import com.xiongda.common.BaseResponse;
import com.xiongda.common.DeleteRequest;
import com.xiongda.common.ErrorCode;
import com.xiongda.common.ResultUtils;
import com.xiongda.exception.BusinessException;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.model.dto.knowledge.KnowledgeBaseAddRequest;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.DocumentVO;
import com.xiongda.model.vo.KnowledgeBaseVO;
import com.xiongda.model.vo.PageContentVO;
import com.xiongda.service.DocumentService;
import com.xiongda.service.KnowledgeBaseService;
import com.xiongda.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库控制器。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private DocumentService documentService;

    @Resource
    private UserService userService;

    /**
     * 查询知识库列表。
     */
    @GetMapping("/list")
    public BaseResponse<List<KnowledgeBaseVO>> listKnowledgeBases(
            @RequestParam(required = false) String scope, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<KnowledgeBaseVO> list = knowledgeBaseService.listKnowledgeBases(
                loginUser.getTenantId(), loginUser.getId(), scope);
        return ResultUtils.success(list);
    }

    /**
     * 创建知识库。
     */
    @PostMapping("/add")
    public BaseResponse<Long> addKnowledgeBase(@RequestBody KnowledgeBaseAddRequest request, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        Long id = knowledgeBaseService.createKnowledgeBase(
                loginUser.getTenantId(), loginUser,
                request.getName(), request.getDescription(), request.getScope());
        return ResultUtils.success(id);
    }

    /**
     * 查询文档列表。
     */
    @GetMapping("/document/list")
    public BaseResponse<List<DocumentVO>> listDocuments(@RequestParam Long kbId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<DocumentVO> list = documentService.listDocuments(kbId, loginUser.getTenantId());
        return ResultUtils.success(list);
    }

    /**
     * 上传文档。
     */
    @PostMapping("/document/upload")
    public BaseResponse<Long> uploadDocument(
            @RequestParam Long kbId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        User loginUser = userService.getLoginUser(request);

        // 保存文件（使用绝对路径，避免 MultipartFile.transferTo 相对路径问题）
        Path uploadDir = Path.of("uploads", String.valueOf(loginUser.getTenantId())).toAbsolutePath();
        Files.createDirectories(uploadDir);
        String originalFilename = file.getOriginalFilename();
        // 修复 Spring/Tomcat 对 multipart 文件名的 ISO-8859-1 解码导致的中文乱码：
        // 仅当文件名全部由 Latin-1 字符组成（疑似被误解码）时才还原，正确中文原样保留。
        if (originalFilename != null
                && StandardCharsets.ISO_8859_1.newEncoder().canEncode(originalFilename)) {
            originalFilename = new String(
                    originalFilename.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
        Path filePath = uploadDir.resolve(UUID.randomUUID() + "_" + originalFilename);
        file.transferTo(filePath.toFile());

        String fileType = getFileExtension(originalFilename);
        Long docId = documentService.uploadDocument(
                kbId, loginUser.getTenantId(), loginUser,
                originalFilename, fileType, file.getSize(), filePath.toString());

        // 异步调用 Python AI 服务处理文档（提取文本 → 分块）
        documentService.triggerDocumentProcessing(
                docId, filePath.toString(), fileType, kbId, loginUser.getTenantId(), loginUser.getId());

        return ResultUtils.success(docId);
    }

    /**
     * 删除文档。
     */
    @PostMapping("/document/delete")
    public BaseResponse<Boolean> deleteDocument(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        boolean result = documentService.deleteDocument(deleteRequest.getId(), loginUser.getTenantId(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 取消文档处理（软取消，保留文档记录）。
     * 仅非终态（处理中/解析中/检索中/优化中）可取消；取消后文档置为「已取消」。
     */
    @PostMapping("/document/cancel")
    public BaseResponse<Boolean> cancelDocument(@RequestBody DeleteRequest cancelRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        boolean result = documentService.cancelDocument(cancelRequest.getId(), loginUser.getTenantId(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 获取文档提取全文（供前端「查看内容」弹窗展示）。
     */
    @GetMapping("/document/content")
    public BaseResponse<String> getDocumentContent(@RequestParam Long docId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        String content = documentService.getDocumentContent(docId, loginUser.getTenantId(), loginUser);
        return ResultUtils.success(content);
    }

    /**
     * 获取文档按页分段的文本（供前端预览真实翻页，M4-4 增强）。
     * PDF 真实页码，docx/txt/md 估算页码，与引用来源页码一致。
     * 权限：同租户可读（与 getDocumentContent 一致）。
     */
    @GetMapping("/document/pages")
    public BaseResponse<List<PageContentVO>> getDocumentPages(
            @RequestParam Long docId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<PageContentVO> pages = documentService.getDocumentPages(docId, loginUser.getTenantId(), loginUser);
        return ResultUtils.success(pages);
    }

    /**
     * 获取文档原始文件流（供前端文件预览弹窗，M4-4）。
     * PDF 以 application/pdf 返回，浏览器可用 iframe 内嵌预览；
     * 其他类型以 application/octet-stream 返回，可触发浏览器下载或文本展示。
     * 权限：同租户可读（与 getDocumentContent 一致）。
     *
     * 注意：文件缺失/读取失败均抛出业务异常（由全局异常处理器转为 JSON），
     * 避免 IOException 穿透到容器渲染 Whitelabel 错误页。
     */
    @GetMapping("/document/file/{docId}")
    public ResponseEntity<InputStreamResource> getDocumentFile(
            @PathVariable Long docId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Document doc = documentService.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!loginUser.getTenantId().equals(doc.getTenantId()),
                ErrorCode.NO_AUTH_ERROR, "无权限查看该文档");

        Path filePath = Path.of(doc.getFilePath());
        ThrowUtils.throwIf(!Files.exists(filePath), ErrorCode.NOT_FOUND_ERROR,
                "原文件已被清理或删除，无法预览，请重新上传该文档");

        String contentType = "pdf".equalsIgnoreCase(doc.getFileType())
                ? "application/pdf"
                : "application/octet-stream";

        InputStreamResource resource;
        try {
            resource = new InputStreamResource(new FileInputStream(filePath.toFile()));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                    "原文件读取失败，可能被清理或权限不足，请重新上传该文档");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + doc.getFilename() + "\"")
                .body(resource);
    }

    /**
     * 校验文档原文件是否可预览（删除 / 被修改检测），供前端预览弹窗前置判断。
     *
     * <p>前端在加载文件流 iframe 前先调用本接口：若文件已删除则提示用户重新上传，
     * 若文件被修改（大小与上传时不一致）则提示可能与知识库索引不一致，避免直接
     * 加载文件流触发 Whitelabel 错误页。权限：同租户可读。</p>
     */
    @GetMapping("/document/file/status/{docId}")
    public BaseResponse<Map<String, Object>> checkDocumentFileStatus(
            @PathVariable Long docId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Document doc = documentService.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!loginUser.getTenantId().equals(doc.getTenantId()),
                ErrorCode.NO_AUTH_ERROR, "无权限查看该文档");

        Path filePath = Path.of(doc.getFilePath());
        Map<String, Object> status = new HashMap<>();
        status.put("filename", doc.getFilename());
        status.put("fileType", doc.getFileType());
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            status.put("exists", false);
            status.put("changed", false);
            status.put("message", "原文件已被清理或删除，无法预览，请重新上传该文档");
            return ResultUtils.success(status);
        }
        // 文件被修改（大小与上传时记录不一致）检测
        long currentSize;
        try {
            currentSize = Files.size(filePath);
        } catch (IOException e) {
            status.put("exists", false);
            status.put("changed", false);
            status.put("message", "原文件读取失败，可能被清理或权限不足，请重新上传该文档");
            return ResultUtils.success(status);
        }
        boolean changed = doc.getFileSize() != null && currentSize != doc.getFileSize();
        status.put("exists", true);
        status.put("changed", changed);
        if (changed) {
            status.put("message", "检测发现原文件已被修改，预览内容可能与知识库索引不一致，建议重新上传该文档");
        }
        return ResultUtils.success(status);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
