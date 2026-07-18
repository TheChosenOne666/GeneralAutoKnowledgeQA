package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.mapper.ChatAttachmentMapper;
import com.xiongda.model.entity.ChatAttachment;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.ChatAttachmentVO;
import com.xiongda.service.ChatAttachmentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 问答附件服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Service
public class ChatAttachmentServiceImpl extends ServiceImpl<ChatAttachmentMapper, ChatAttachment>
        implements ChatAttachmentService {

    /**
     * 允许的附件分类。image 走 vision 多模态，attachment 走文档提取。
     */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("image", "attachment");

    /**
     * 图片分类允许的文件扩展名（用于校验 image 分类上传的确实是图片）。
     */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    @Resource
    private com.xiongda.service.UserService userService;

    @Override
    public ChatAttachment uploadAttachment(MultipartFile file, String category, String originalFilename, User user) {
        // 参数校验：分类必须合法
        ThrowUtils.throwIf(category == null || !ALLOWED_CATEGORIES.contains(category),
                ErrorCode.PARAMS_ERROR, "附件分类非法，仅支持 image / attachment");
        ThrowUtils.throwIf(file == null || file.isEmpty(),
                ErrorCode.PARAMS_ERROR, "上传文件为空");

        // 优先用前端传的 originalFilename（避免 Tomcat multipart 中文乱码），回退到 file.getOriginalFilename()
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = file.getOriginalFilename();
        }
        ThrowUtils.throwIf(originalFilename == null || originalFilename.isBlank(),
                ErrorCode.PARAMS_ERROR, "文件名不能为空");

        // 前端用 encodeURIComponent 编码了中文文件名，这里解码还原
        try {
            originalFilename = java.net.URLDecoder.decode(originalFilename, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解码失败说明不是编码过的，保持原样
            log.warn("originalFilename URL 解码失败，保持原样: {}", originalFilename);
        }

        String fileType = getFileExtension(originalFilename);

        // 图片分类校验：必须是图片扩展名，避免用户在图片按钮处误传非图片
        if ("image".equals(category)) {
            ThrowUtils.throwIf(!IMAGE_EXTENSIONS.contains(fileType),
                    ErrorCode.PARAMS_ERROR, "图片按钮仅支持 png/jpg/jpeg/gif/webp/bmp 格式");
        }

        // 存盘：uploads/{tenantId}/chat-attachments/{category}/{uuid}.{ext}
        // 用纯 ASCII 文件名存盘，避免中文文件名导致 Path 非法字符异常
        Path uploadDir = Path.of("uploads", String.valueOf(user.getTenantId()),
                "chat-attachments", category).toAbsolutePath();
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.error("创建附件目录失败 dir={}: {}", uploadDir, e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建附件目录失败");
        }
        String safeFileName = UUID.randomUUID() + (fileType.isEmpty() ? "" : "." + fileType);
        Path filePath = uploadDir.resolve(safeFileName);
        try {
            file.transferTo(filePath.toFile());
        } catch (IOException e) {
            log.error("保存附件失败 file={}: {}", originalFilename, e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存附件失败");
        }

        // 落库（filename 存原始中文文件名，filePath 存纯 ASCII 路径）
        ChatAttachment attachment = new ChatAttachment();
        attachment.setTenantId(user.getTenantId());
        attachment.setUserId(user.getId());
        attachment.setFilename(originalFilename);
        attachment.setFileType(fileType);
        attachment.setFilePath(filePath.toString());
        attachment.setFileSize(file.getSize());
        attachment.setCategory(category);
        this.save(attachment);
        log.info("[问答附件] 上传成功 id={} category={} fileType={} tenantId={} userId={}",
                attachment.getId(), category, fileType, user.getTenantId(), user.getId());
        return attachment;
    }

    @Override
    public ChatAttachment getByIdAndTenant(Long id, Long tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        QueryWrapper<ChatAttachment> qw = new QueryWrapper<>();
        qw.eq("id", id).eq("tenant_id", tenantId);
        return this.getOne(qw);
    }

    @Override
    public List<ChatAttachment> listByIdsAndTenant(List<Long> ids, Long tenantId, String category) {
        if (ids == null || ids.isEmpty() || tenantId == null) {
            return Collections.emptyList();
        }
        QueryWrapper<ChatAttachment> qw = new QueryWrapper<>();
        qw.in("id", ids).eq("tenant_id", tenantId);
        if (category != null) {
            qw.eq("category", category);
        }
        List<ChatAttachment> records = this.list(qw);
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        // 按 ids 顺序返回（保持前端传入顺序，便于调试）
        Map<Long, ChatAttachment> byId = new LinkedHashMap<>();
        for (ChatAttachment a : records) {
            byId.put(a.getId(), a);
        }
        List<ChatAttachment> ordered = new ArrayList<>();
        for (Long id : ids) {
            ChatAttachment a = byId.get(id);
            if (a != null) {
                ordered.add(a);
            }
        }
        return ordered;
    }

    /**
     * 把附件实体转为 VO（含访问 URL）。
     */
    public ChatAttachmentVO toVO(ChatAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        ChatAttachmentVO vo = new ChatAttachmentVO();
        vo.setId(attachment.getId());
        vo.setFilename(attachment.getFilename());
        vo.setFileType(attachment.getFileType());
        vo.setFileSize(attachment.getFileSize());
        vo.setCategory(attachment.getCategory());
        vo.setUrl("/api/chat/attachment/" + attachment.getId());
        return vo;
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
