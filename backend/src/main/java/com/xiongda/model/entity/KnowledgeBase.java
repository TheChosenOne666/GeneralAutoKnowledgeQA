package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 知识库实体。
 * scope=shared: 共享知识库，租户管理员维护，全员可问答，普通成员只读。
 * scope=personal: 个人知识库，仅 owner 可见可问答。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "knowledge_base")
@Data
public class KnowledgeBase implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private String name;

    private String description;

    /**
     * shared / personal
     */
    private String scope;

    private Long ownerId;

    private Integer documentCount;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer isDelete;
}
