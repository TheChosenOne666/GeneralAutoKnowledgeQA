package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 问答附件返回视图。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class ChatAttachmentVO implements Serializable {

    private Long id;

    private String filename;

    private String fileType;

    private Long fileSize;

    /**
     * 分类：image / attachment。
     */
    private String category;

    /**
     * 文件访问 URL（前端展示用，携带 JWT 鉴权）。
     */
    private String url;
}
