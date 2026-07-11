package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 会话视图对象。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class ConversationVO implements Serializable {

    private Long id;

    private String title;

    private Integer messageCount;

    private Date createTime;

    private Date updateTime;
}
