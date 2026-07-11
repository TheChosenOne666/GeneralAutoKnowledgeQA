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

    private Long id;

    private String role;

    private String content;

    private String sources;

    private String model;

    private Date createTime;
}
