package com.xiongda.model.dto.chat;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 问答请求。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class ChatRequest implements Serializable {

    private Long conversationId;

    private String content;

    private List<Long> kbIds;

    private String model;

    /**
     * rag / search
     */
    private String mode = "rag";
}
