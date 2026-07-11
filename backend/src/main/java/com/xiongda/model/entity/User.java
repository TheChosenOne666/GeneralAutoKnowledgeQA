package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户实体。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "user")
@Data
public class User implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 租户 ID（super_admin 为 null）
     */
    private Long tenantId;

    private String name;

    private String email;

    private String userPassword;

    /**
     * member / tenant_admin / super_admin
     */
    private String role;

    private Integer isActive;

    private String avatarUrl;

    private Date lastActiveAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer isDelete;
}
