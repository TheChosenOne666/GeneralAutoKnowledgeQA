package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 消息视图对象。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class MessageVO implements Serializable {

    private String id;  // 雪花 ID 超长，用字符串避免前端 JS 精度丢失

    private String role;

    private String content;

    private String sources;

    private String model;

    private Date createTime;
}
