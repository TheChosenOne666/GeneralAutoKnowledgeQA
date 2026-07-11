package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.mapper.ConversationMapper;
import com.xiongda.mapper.MessageMapper;
import com.xiongda.model.entity.Conversation;
import com.xiongda.model.entity.Message;
import com.xiongda.model.vo.ConversationVO;
import com.xiongda.model.vo.MessageVO;
import com.xiongda.service.ChatService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问答服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Service
public class ChatServiceImpl extends ServiceImpl<ConversationMapper, Conversation> implements ChatService {

    private final MessageMapper messageMapper;

    public ChatServiceImpl(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public Long createConversation(Long tenantId, Long userId, String title) {
        Conversation conv = new Conversation();
        conv.setTenantId(tenantId);
        conv.setUserId(userId);
        conv.setTitle(StringUtils.isNotBlank(title) ? title : "新对话");
        conv.setMessageCount(0);
        this.save(conv);
        return conv.getId();
    }

    @Override
    public List<ConversationVO> listConversations(Long userId) {
        QueryWrapper<Conversation> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        queryWrapper.orderByDesc("update_time");
        List<Conversation> convs = this.list(queryWrapper);
        return convs.stream().map(this::toConversationVO).toList();
    }

    @Override
    public List<MessageVO> listMessages(Long conversationId) {
        QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("conversation_id", conversationId);
        queryWrapper.orderByAsc("create_time");
        List<Message> messages = messageMapper.selectList(queryWrapper);
        return messages.stream().map(this::toMessageVO).toList();
    }

    @Override
    public void saveUserMessage(Long conversationId, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent(content);
        messageMapper.insert(message);
    }

    @Override
    public void saveAssistantMessage(Long conversationId, String content, String model, String sources) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole("assistant");
        message.setContent(content);
        message.setModel(model);
        message.setSources(sources);
        messageMapper.insert(message);
    }

    private ConversationVO toConversationVO(Conversation conv) {
        ConversationVO vo = new ConversationVO();
        vo.setId(conv.getId());
        vo.setTitle(conv.getTitle());
        vo.setMessageCount(conv.getMessageCount());
        vo.setCreateTime(conv.getCreateTime());
        vo.setUpdateTime(conv.getUpdateTime());
        return vo;
    }

    private MessageVO toMessageVO(Message msg) {
        MessageVO vo = new MessageVO();
        vo.setId(msg.getId());
        vo.setRole(msg.getRole());
        vo.setContent(msg.getContent());
        vo.setSources(msg.getSources());
        vo.setModel(msg.getModel());
        vo.setCreateTime(msg.getCreateTime());
        return vo;
    }
}
