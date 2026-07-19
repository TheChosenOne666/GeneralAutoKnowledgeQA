package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 租户实体 — 多租户隔离顶层。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "tenant")
@Data
public class Tenant implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String slug;

    /**
     * active / suspended
     */
    private String status;

    private Integer maxMembers;

    private Integer maxDocuments;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * M6-1：检索配置 JSONB（rrf_k, rrf_vector_weight, rrf_keyword_weight 等）。
     * NULL 表示用默认值，Python 端走 settings 兜底。
     */
    private String retrievalConfig;

    @TableLogic
    private Integer isDelete;
}
