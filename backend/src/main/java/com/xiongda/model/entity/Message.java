package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 消息实体 — 会话中的每条消息（用户提问 / AI 回答）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "message")
@Data
public class Message implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;

    /**
     * user / assistant
     */
    private String role;

    private String content;

    /**
     * 引用来源 JSON
     */
    private String sources;

    /**
     * 使用的模型名称
     */
    private String model;

    /**
     * 使用的知识库 ID 列表 JSON
     */
    private String kbIds;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
