package com.xiongda.controller;

import com.xiongda.common.BaseResponse;
import com.xiongda.common.ErrorCode;
import com.xiongda.common.ResultUtils;
import com.xiongda.exception.BusinessException;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.model.entity.ChatAttachment;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.ChatAttachmentVO;
import com.xiongda.service.ChatAttachmentService;
import com.xiongda.service.UserService;
import com.xiongda.service.impl.ChatAttachmentServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 问答附件控制器 — 上传与获取问答页临时图片 / 附件。
 *
 * <p>与知识库文档上传（KnowledgeBaseController.uploadDocument）区别：
 * 本接口上传的文件不入向量库、不跨会话检索，仅用于本次问答的临时上下文：
 * <ul>
 *   <li>category=image：图片，走 LLM vision 多模态调用；</li>
 *   <li>category=attachment：通用文档（pdf/docx/txt/md），后端提取文本拼到 LLM 上下文。</li>
 * </ul>
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/chat/attachment")
@Slf4j
public class ChatAttachmentController {

    @Resource
    private ChatAttachmentService chatAttachmentService;

    @Resource
    private UserService userService;

    /**
     * 上传问答附件（图片或通用文档）。
     *
     * @param category 分类：image / attachment
     * @param file     文件（multipart/form-data，字段名 file）
     */
    @PostMapping("/upload")
    public BaseResponse<ChatAttachmentVO> upload(
            @RequestParam String category,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "originalFilename", required = false) String originalFilename,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatAttachment attachment = chatAttachmentService.uploadAttachment(file, category, originalFilename, loginUser);
        ChatAttachmentVO vo = ((ChatAttachmentServiceImpl) chatAttachmentService).toVO(attachment);
        return ResultUtils.success(vo);
    }

    /**
     * 获取附件文件流（供前端展示图片 / 下载附件，携带 JWT 鉴权）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> get(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatAttachment attachment = chatAttachmentService.getByIdAndTenant(id, loginUser.getTenantId());
        ThrowUtils.throwIf(attachment == null, ErrorCode.NOT_FOUND_ERROR, "附件不存在或无权限访问");

        Path filePath = Path.of(attachment.getFilePath());
        ThrowUtils.throwIf(!Files.exists(filePath) || !Files.isRegularFile(filePath),
                ErrorCode.NOT_FOUND_ERROR, "附件文件已被清理或删除");

        boolean isImage = "image".equals(attachment.getCategory());
        String contentType;
        try {
            contentType = Files.probeContentType(filePath);
        } catch (IOException e) {
            contentType = null;
        }
        if (contentType == null) {
            contentType = isImage ? MediaType.IMAGE_PNG_VALUE : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        InputStreamResource resource;
        try {
            resource = new InputStreamResource(new FileInputStream(filePath.toFile()));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "附件读取失败，可能被清理或权限不足");
        }

        String contentDisposition = isImage
                ? "inline; filename=\"" + attachment.getFilename() + "\""
                : "attachment; filename=\"" + attachment.getFilename() + "\"";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }
}
