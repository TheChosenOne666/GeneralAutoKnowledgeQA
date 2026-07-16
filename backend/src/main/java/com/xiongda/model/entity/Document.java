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

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMsg;

    /**
     * 文档提取全文（Python 解析后回填，供前端「查看内容」弹窗展示）。
     */
    @TableField("content")
    private String content;

    /**
     * 是否因 AI 模型配置错误导致处理失败（M3-3，引导用户到 /ai-config 重新配置）。
     * 仅当 status=failed 时可能为 true。
     */
    @TableField(value = "model_config_error", updateStrategy = FieldStrategy.ALWAYS)
    private Boolean modelConfigError;

    /**
     * 是否因模型额度不足 / 被限流（HTTP 429 / 5xx 过载 / 余额耗尽）导致处理失败。
     * 与 modelConfigError 区分：本字段表示「额度问题」而非「配置错误」，
     * 前端据此提示「稍后重试或检查账户额度」，而非误导用户去重配模型。
     * 仅当 status=failed 时可能为 true。
     */
    @TableField(value = "quota_error", updateStrategy = FieldStrategy.ALWAYS)
    private Boolean quotaError;

    private Long uploadedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer isDelete;
}
