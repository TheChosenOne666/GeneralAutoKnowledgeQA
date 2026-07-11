package com.xiongda.model.dto.chat;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

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

    /**
     * 多轮对话历史（不含当前问题），透传给 Python AI 服务。
     */
    private List<Map<String, String>> history;
}
