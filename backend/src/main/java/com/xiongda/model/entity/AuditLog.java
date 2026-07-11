package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志实体。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "audit_log")
@Data
public class AuditLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * super_admin 操作可能为 null
     */
    private Long tenantId;

    private Long userId;

    private String userEmail;

    /**
     * login / logout / doc_upload / doc_delete / config_update / member_change
     */
    private String action;

    private String resourceType;

    private String resourceId;

    /**
     * 详情 JSON
     */
    private String detail;

    private String ipAddress;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
