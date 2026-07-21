package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongda.client.AiServiceClient;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.mapper.ConversationMapper;
import com.xiongda.mapper.MessageMapper;
import com.xiongda.model.entity.Conversation;
import com.xiongda.model.entity.Message;
import com.xiongda.model.vo.ConversationVO;
import com.xiongda.model.vo.MessageVO;
import com.xiongda.service.ChatService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 问答服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Service
public class ChatServiceImpl extends ServiceImpl<ConversationMapper, Conversation> implements ChatService {

    private final MessageMapper messageMapper;
    private final AiServiceClient aiServiceClient;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CONV_CACHE_PREFIX = "chat:conv:";
    private static final long CONV_CACHE_TTL = 1800; // 会话缓存 TTL：30min
    private static final int CONV_CACHE_MAX = 50;    // 会话缓存最多保留最近 50 条

    public ChatServiceImpl(MessageMapper messageMapper, AiServiceClient aiServiceClient) {
        this.messageMapper = messageMapper;
        this.aiServiceClient = aiServiceClient;
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
    public List<MessageVO> listMessages(Long conversationId, Long userId) {
        // 归属校验：会话必须属于当前登录用户，防止越权读取他人对话（含跨账号串号）
        Conversation conv = this.getById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        String key = CONV_CACHE_PREFIX + conversationId;
        try {
            List<String> cached = redisTemplate.opsForList().range(key, 0, -1);
            if (cached != null && !cached.isEmpty()) {
                // 命中 L3 会话缓存，直接返回，跳过数据库查询
                List<MessageVO> vos = new ArrayList<>();
                for (int i = 0; i < cached.size(); i++) {
                    Map<String, Object> entry = objectMapper.readValue(cached.get(i), Map.class);
                    MessageVO vo = new MessageVO();
                    vo.setId(conversationId + ":" + i);
                    vo.setRole((String) entry.get("role"));
                    vo.setContent((String) entry.get("content"));
                    vo.setSources((String) entry.get("sources"));
                    vo.setModel((String) entry.get("model"));
                    // 缓存回放需携带 createTime，否则前端消息时间丢失
                    Object ct = entry.get("createTime");
                    if (ct instanceof Number) {
                        vo.setCreateTime(new Date(((Number) ct).longValue()));
                    }
                    vos.add(vo);
                }
                return vos;
            }
        } catch (Exception e) {
            log.warn("读会话 Redis 缓存失败，回源数据库: {}", e.getMessage());
        }
        // 未命中：回源数据库
        QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("conversation_id", conversationId);
        queryWrapper.orderByAsc("create_time");
        List<Message> messages = messageMapper.selectList(queryWrapper);
        List<MessageVO> vos = messages.stream().map(this::toMessageVO).toList();
        // 回填 L3 会话缓存
        try {
            for (Message m : messages) {
                cacheMessage(conversationId, m.getRole(), m.getContent(), m.getSources(), m.getModel(), m.getCreateTime());
            }
        } catch (Exception e) {
            log.warn("回填会话 Redis 缓存失败，忽略: {}", e.getMessage());
        }
        return vos;
    }

    @Override
    public void saveUserMessage(Long conversationId, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent(content);
        messageMapper.insert(message);
        cacheMessage(conversationId, "user", content, null, null, message.getCreateTime());
        // 异步索引到 ES（全局搜索用，失败不阻塞）
        indexMessageToEs(message, conversationId, content);
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
        cacheMessage(conversationId, "assistant", content, sources, model, message.getCreateTime());
        // 异步索引到 ES（全局搜索用，失败不阻塞）
        indexMessageToEs(message, conversationId, content);
    }

    @Override
    public void renameConversation(Long id, Long userId, String title) {
        Conversation conv = this.getById(id);
        if (conv == null || !userId.equals(conv.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        conv.setTitle(title);
        this.updateById(conv);
    }

    @Override
    public void deleteConversation(Long id, Long userId) {
        Conversation conv = this.getById(id);
        if (conv == null || !userId.equals(conv.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        QueryWrapper<Message> qw = new QueryWrapper<>();
        qw.eq("conversation_id", id);
        messageMapper.delete(qw);
        this.removeById(id);
        // 清理 L3 会话缓存
        try {
            redisTemplate.delete(CONV_CACHE_PREFIX + id);
        } catch (Exception e) {
            log.warn("清理会话 Redis 缓存失败，忽略: {}", e.getMessage());
        }
        // 清理 ES 消息索引（全局搜索用，失败不阻塞）
        try {
            aiServiceClient.deleteConversationMessages(
                    String.valueOf(id),
                    conv.getTenantId() != null ? String.valueOf(conv.getTenantId()) : ""
            );
        } catch (Exception e) {
            log.warn("清理 ES 消息索引失败，忽略: {}", e.getMessage());
        }
    }

    /**
     * 写会话消息到 Redis 会话缓存（L3）：RPUSH + 保留最近 N 条 + 刷新 TTL。
     * Redis 不可用时忽略，不阻塞主流程。
     */
    private void cacheMessage(Long conversationId, String role, String content, String sources, String model, Date createTime) {
        try {
            String key = CONV_CACHE_PREFIX + conversationId;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", role);
            entry.put("content", content);
            entry.put("sources", sources);
            entry.put("model", model);
            entry.put("createTime", createTime != null ? createTime.getTime() : null);
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(entry));
            redisTemplate.opsForList().trim(key, -CONV_CACHE_MAX, -1);
            redisTemplate.expire(key, CONV_CACHE_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写会话 Redis 缓存失败，忽略: {}", e.getMessage());
        }
    }

    private ConversationVO toConversationVO(Conversation conv) {
        ConversationVO vo = new ConversationVO();
        vo.setId(String.valueOf(conv.getId()));
        vo.setTitle(conv.getTitle());
        vo.setMessageCount(conv.getMessageCount());
        vo.setCreateTime(conv.getCreateTime());
        vo.setUpdateTime(conv.getUpdateTime());
        return vo;
    }

    /**
     * 异步索引消息到 ES（全局搜索用）。
     * 获取会话标题用于搜索结果展示。失败不阻塞主流程（AiServiceClient 内部已捕获异常）。
     */
    private void indexMessageToEs(Message message, Long conversationId, String content) {
        try {
            Conversation conv = this.getById(conversationId);
            String convTitle = conv != null ? conv.getTitle() : "";
            String tenantId = conv != null && conv.getTenantId() != null ? String.valueOf(conv.getTenantId()) : "";
            String userId = conv != null && conv.getUserId() != null ? String.valueOf(conv.getUserId()) : "";
            String createTime = message.getCreateTime() != null ? String.valueOf(message.getCreateTime().getTime()) : "";
            aiServiceClient.indexMessage(
                    String.valueOf(message.getId()),
                    String.valueOf(conversationId),
                    convTitle,
                    message.getRole(),
                    content,
                    tenantId,
                    userId,
                    createTime
            );
        } catch (Exception e) {
            log.warn("索引消息到 ES 失败，忽略: {}", e.getMessage());
        }
    }

    private MessageVO toMessageVO(Message msg) {
        MessageVO vo = new MessageVO();
        vo.setId(String.valueOf(msg.getId()));
        vo.setRole(msg.getRole());
        vo.setContent(msg.getContent());
        vo.setSources(msg.getSources());
        vo.setModel(msg.getModel());
        vo.setCreateTime(msg.getCreateTime());
        return vo;
    }
}
