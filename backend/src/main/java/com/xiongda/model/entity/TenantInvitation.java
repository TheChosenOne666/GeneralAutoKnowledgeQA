package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 租户邀请（share-link 模式，可多人复用）。
 *
 * <p>状态机：pending → accepted | revoked | expired。邀请链接生成后保持 pending 可多人复用，
 * {@code acceptedCount} 记录已加入人数；过期走懒校验（接受时判断 expiresAt）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "tenant_invitations")
@Data
public class TenantInvitation implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属租户
     */
    private Long tenantId;

    /**
     * 邀请人 ID
     */
    private Long inviterId;

    /**
     * 邀请时填写的建议姓名（接受者注册时可沿用或修改）
     */
    private String inviteeName;

    /**
     * 邀请时填写的建议邮箱
     */
    private String inviteeEmail;

    /**
     * 加入后的角色：member / tenant_admin
     */
    private String role;

    /**
     * 一次性随机令牌（明文存库，仅用于拼接邀请链接，绝不出现在除 invite_url 外的响应中）
     */
    private String token;

    /**
     * pending / accepted / revoked / expired
     */
    private String status;

    /**
     * 已通过此链接加入的人数（share-link 可复用）
     */
    private Integer acceptedCount;

    /**
     * 过期时间
     */
    private Date expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer isDelete;
}
