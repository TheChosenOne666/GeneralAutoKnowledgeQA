package com.xiongda.model.dto.chat;

import lombok.Data;

import java.io.Serializable;

/**
 * 重命名会话请求。
 */
@Data
public class RenameConversationRequest implements Serializable {
    private Long id;
    private String title;
}
