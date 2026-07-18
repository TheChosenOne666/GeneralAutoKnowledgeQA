package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.ChatAttachment;
import com.xiongda.model.entity.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 问答附件服务接口 — 管理问答页临时上传的图片 / 附件。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface ChatAttachmentService extends IService<ChatAttachment> {

    /**
     * 上传问答附件（图片或通用文档）。
     *
     * <p>存盘到 {@code uploads/{tenantId}/chat-attachments/{category}/{uuid}_{filename}}，
     * 并落库一条 ChatAttachment 记录，返回 VO（含访问 URL）。</p>
     *
     * @param file             前端上传的文件
     * @param category         分类：{@code image} 或 {@code attachment}
     * @param originalFilename 前端传的原始文件名（解决 Tomcat multipart 中文乱码），可为 null 时回退到 file.getOriginalFilename()
     * @param user             当前登录用户（取 tenantId / userId 隔离）
     * @return 附件 VO（含 id 与访问 url）
     */
    ChatAttachment uploadAttachment(MultipartFile file, String category, String originalFilename, User user);

    /**
     * 按 ID 查询附件（带租户隔离校验）。
     *
     * @param id       附件 ID
     * @param tenantId 当前租户 ID
     * @return 附件实体；不存在或跨租户返回 null
     */
    ChatAttachment getByIdAndTenant(Long id, Long tenantId);

    /**
     * 批量按 ID 查询附件路径（带租户隔离校验，供 chat_stream 解析 imageIds/attachmentIds 为文件路径用）。
     *
     * @param ids       附件 ID 列表
     * @param tenantId  当前租户 ID
     * @param category  限定分类（image / attachment），与请求字段对应；为 null 时不限定
     * @return 附件实体列表（仅返回同租户且分类匹配的记录），按 ids 顺序返回
     */
    List<ChatAttachment> listByIdsAndTenant(List<Long> ids, Long tenantId, String category);
}
