package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 问答附件实体 — 用户在问答页输入框临时上传的图片或附件（不入知识库，仅本次问答使用）。
 *
 * <p>与 {@link Document} 区别：
 * <ul>
 *   <li>{@code Document} 是知识库持久化文档，向量化入库、跨会话可被 RAG 检索；</li>
 *   <li>{@code ChatAttachment} 是问答时一次性临时上下文：图片走 LLM vision 多模态调用，
 *       通用文档（pdf/docx/txt/md）提取文本拼到本次 LLM 上下文，不入向量库、不跨会话。</li>
 * </ul>
 *
 * <p>不做逻辑删除（临时文件，可定期清理过期记录）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "chat_attachment")
@Data
public class ChatAttachment implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private Long userId;

    /**
     * 原始文件名（前端展示用）。
     */
    private String filename;

    /**
     * 文件类型扩展名（小写，无点）：png/jpg/pdf/docx/txt/md 等。
     */
    private String fileType;

    /**
     * 服务器绝对路径。
     */
    private String filePath;

    private Long fileSize;

    /**
     * 附件分类：{@code image}（图片，走 vision 多模态）或 {@code attachment}（通用文档，提取文本拼上下文）。
     */
    private String category;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
