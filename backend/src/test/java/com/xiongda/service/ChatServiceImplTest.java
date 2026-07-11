package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.mapper.ConversationMapper;
import com.xiongda.mapper.MessageMapper;
import com.xiongda.model.entity.Conversation;
import com.xiongda.model.entity.Message;
import com.xiongda.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 会话服务实现单元测试 — 覆盖重命名、删除（含归属校验）。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageMapper messageMapper;

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(messageMapper);
        ReflectionTestUtils.setField(chatService, "baseMapper", conversationMapper);
    }

    @Test
    void renameConversation_success() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId(10L);
        conv.setTitle("旧标题");
        when(conversationMapper.selectById(1L)).thenReturn(conv);
        when(conversationMapper.updateById(any(Conversation.class))).thenReturn(1);

        chatService.renameConversation(1L, 10L, "新标题");

        assertEquals("新标题", conv.getTitle());
        verify(conversationMapper).updateById(conv);
    }

    @Test
    void renameConversation_notOwner_throws() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId(99L);
        when(conversationMapper.selectById(1L)).thenReturn(conv);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.renameConversation(1L, 10L, "新标题"));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
    }

    @Test
    void deleteConversation_success() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId(10L);
        when(conversationMapper.selectById(1L)).thenReturn(conv);
        when(messageMapper.delete(any(QueryWrapper.class))).thenReturn(2);
        when(conversationMapper.deleteById(1L)).thenReturn(1);

        chatService.deleteConversation(1L, 10L);

        verify(messageMapper).delete(any(QueryWrapper.class));
        verify(conversationMapper).deleteById(1L);
    }

    @Test
    void deleteConversation_notOwner_throws() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId(99L);
        when(conversationMapper.selectById(1L)).thenReturn(conv);

        assertThrows(BusinessException.class, () -> chatService.deleteConversation(1L, 10L));
    }
}
