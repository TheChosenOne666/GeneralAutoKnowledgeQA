package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.Conversation;
import com.xiongda.model.vo.ConversationVO;
import com.xiongda.model.vo.MessageVO;

import java.util.List;

/**
 * 问答服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface ChatService extends IService<Conversation> {

    /**
     * 创建会话。
     */
    Long createConversation(Long tenantId, Long userId, String title);

    /**
     * 获取用户会话列表。
     */
    List<ConversationVO> listConversations(Long userId);

    /**
     * 获取会话消息列表。
     */
    List<MessageVO> listMessages(Long conversationId);

    /**
     * 保存用户消息。
     */
    void saveUserMessage(Long conversationId, String content);

    /**
     * 保存 AI 消息。
     */
    void saveAssistantMessage(Long conversationId, String content, String model, String sources);
}
