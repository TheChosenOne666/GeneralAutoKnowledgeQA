package com.xiongda.controller;

import com.xiongda.common.BaseResponse;
import com.xiongda.common.DeleteRequest;
import com.xiongda.common.ResultUtils;
import com.xiongda.model.dto.knowledge.KnowledgeBaseAddRequest;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.DocumentVO;
import com.xiongda.model.vo.KnowledgeBaseVO;
import com.xiongda.service.DocumentService;
import com.xiongda.service.KnowledgeBaseService;
import com.xiongda.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                loginUser.getTenantId(), loginUser.getId(),
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
        Path filePath = uploadDir.resolve(UUID.randomUUID() + "_" + originalFilename);
        file.transferTo(filePath.toFile());

        String fileType = getFileExtension(originalFilename);
        Long docId = documentService.uploadDocument(
                kbId, loginUser.getTenantId(), loginUser.getId(),
                originalFilename, fileType, file.getSize(), filePath.toString());

        // TODO: 异步调用 AiServiceClient.processDocument 触发文档处理

        return ResultUtils.success(docId);
    }

    /**
     * 删除文档。
     */
    @PostMapping("/document/delete")
    public BaseResponse<Boolean> deleteDocument(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        boolean result = documentService.deleteDocument(deleteRequest.getId(), loginUser.getTenantId());
        return ResultUtils.success(result);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
