package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.mapper.ConversationMapper;
import com.xiongda.mapper.MessageMapper;
import com.xiongda.model.entity.Conversation;
import com.xiongda.model.entity.Message;
import com.xiongda.model.vo.MessageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L3 会话 Redis 缓存单元测试（纯 Mockito，不加载 Spring 上下文）。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationMapper baseMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(messageMapper);
        ReflectionTestUtils.setField(chatService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(chatService, "baseMapper", baseMapper);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
    }

    @Test
    void saveUserMessage_writesRedisThenDb() {
        chatService.saveUserMessage(1L, "hi");
        verify(listOps).rightPush(eq("chat:conv:1"), anyString());
        verify(listOps).trim(eq("chat:conv:1"), eq(-50L), eq(-1L));
        verify(redisTemplate).expire(eq("chat:conv:1"), eq(1800L), eq(TimeUnit.SECONDS));
        verify(messageMapper).insert(any(Message.class));
    }

    @Test
    void saveAssistantMessage_writesRedisThenDb() {
        chatService.saveAssistantMessage(1L, "answer", "doubao", "[{\"source\":\"a\"}]");
        verify(listOps).rightPush(eq("chat:conv:1"), anyString());
        verify(redisTemplate).expire(eq("chat:conv:1"), eq(1800L), eq(TimeUnit.SECONDS));
        verify(messageMapper).insert(any(Message.class));
    }

    @Test
    void listMessages_hitCache_skipsDb() {
        Conversation conv = new Conversation();
        conv.setUserId(1L);
        when(baseMapper.selectById(1L)).thenReturn(conv);
        String json = "{\"role\":\"user\",\"content\":\"hi\"}";
        when(listOps.range(eq("chat:conv:1"), anyLong(), anyLong())).thenReturn(List.of(json));
        List<MessageVO> vos = chatService.listMessages(1L, 1L);
        verify(messageMapper, never()).selectList(any(QueryWrapper.class));
        assertEquals(1, vos.size());
        assertEquals("hi", vos.get(0).getContent());
    }

    @Test
    void listMessages_miss_fallsBackToDbAndRefills() {
        Conversation conv = new Conversation();
        conv.setUserId(1L);
        when(baseMapper.selectById(1L)).thenReturn(conv);
        when(listOps.range(eq("chat:conv:1"), anyLong(), anyLong())).thenReturn(List.of());
        Message msg = new Message();
        msg.setRole("user");
        msg.setContent("hi");
        when(messageMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(msg));
        List<MessageVO> vos = chatService.listMessages(1L, 1L);
        verify(messageMapper).selectList(any(QueryWrapper.class));
        verify(listOps).rightPush(eq("chat:conv:1"), anyString());
        assertEquals(1, vos.size());
    }

    @Test
    void listMessages_otherUser_throwsNotFound() {
        // 越权防护：会话属于用户 2，但当前登录用户为 1，应拒绝
        Conversation conv = new Conversation();
        conv.setUserId(2L);
        when(baseMapper.selectById(1L)).thenReturn(conv);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.listMessages(1L, 1L));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
        verify(messageMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    void deleteConversation_clearsRedisCache() {
        Conversation conv = new Conversation();
        conv.setUserId(1L);
        when(baseMapper.selectById(1L)).thenReturn(conv);
        when(messageMapper.delete(any(QueryWrapper.class))).thenReturn(0);
        when(baseMapper.deleteById(1L)).thenReturn(1);
        chatService.deleteConversation(1L, 1L);
        verify(redisTemplate).delete("chat:conv:1");
    }
}
