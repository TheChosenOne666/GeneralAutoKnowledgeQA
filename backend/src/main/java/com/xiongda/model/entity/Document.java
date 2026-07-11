package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 文档实体 — 知识库中的文件，含处理状态。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "document")
@Data
public class Document implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private Long tenantId;

    private String filename;

    /**
     * pdf / docx / md / txt
     */
    private String fileType;

    private Long fileSize;

    private String filePath;

    /**
     * pending / parsing / embedding / ready / failed
     */
    private String status;

    private Integer chunkCount;

    private String errorMsg;

    private Long uploadedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer isDelete;
}
