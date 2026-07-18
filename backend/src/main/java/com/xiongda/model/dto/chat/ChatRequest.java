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
     * rag / web / agent（M4-3 新增 web 联网搜索模式）
     */
    private String mode = "rag";

    /**
     * 多轮对话历史（不含当前问题），透传给 Python AI 服务。
     */
    private List<Map<String, String>> history;

    /**
     * 问答时携带的图片附件 ID 列表（M5-9 多模态问答）。
     *
     * <p>前端用户在输入框点「图片」按钮上传后获得附件 ID，发送问答时一起携带；
     * Java 解析为文件绝对路径透传给 Python，由 Python 把图片转 base64 调 LLM vision。
     * 仅当用户配置的 LLM 支持多模态时有效（前端在 AI 配置页有提示）。</p>
     */
    private List<Long> imageIds;

    /**
     * 问答时携带的通用文档附件 ID 列表（M5-9 一次性文档问答）。
     *
     * <p>前端用户在输入框点「附件」按钮上传后获得附件 ID，发送问答时一起携带；
     * Java 解析为文件绝对路径透传给 Python，由 Python 复用 document_processor 提取文本
     * 拼到本次 LLM 上下文，不入向量库、不跨会话。</p>
     */
    private List<Long> attachmentIds;
}
