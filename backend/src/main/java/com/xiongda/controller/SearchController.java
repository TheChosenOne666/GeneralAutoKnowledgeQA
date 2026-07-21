package com.xiongda.controller;

import com.xiongda.annotation.AuthCheck;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.ResultUtils;
import com.xiongda.client.AiServiceClient;
import com.xiongda.mapper.ConversationMapper;
import com.xiongda.mapper.KnowledgeBaseMapper;
import com.xiongda.mapper.MessageMapper;
import com.xiongda.model.entity.Conversation;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.entity.Message;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 全局搜索接口 — 搜索范围：文档 chunk（ES BM25）+ 聊天消息（ES BM25）。
 *
 * <p>知识库名称和会话标题由前端本地过滤（数据量小，无需走后端搜索）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final AiServiceClient aiServiceClient;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Resource
    private HttpServletRequest httpRequest;

    public SearchController(
            AiServiceClient aiServiceClient,
            MessageMapper messageMapper,
            ConversationMapper conversationMapper,
            KnowledgeBaseMapper knowledgeBaseMapper
    ) {
        this.aiServiceClient = aiServiceClient;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    /**
     * 全局搜索 — 文档 chunk + 聊天消息。
     *
     * <p>文档 chunk 搜索走 Python AI 服务（ES BM25）。
     * 聊天消息搜索走 Python AI 服务（ES BM25），ES 不可用时回退到 PG ILIKE。
     *
     * @param query 搜索关键词
     * @param kbIds 限定搜索的知识库范围（可选，null = 全部）
     */
    @GetMapping("/global")
    @AuthCheck
    public BaseResponse<Map<String, Object>> globalSearch(
            @RequestParam String query,
            @RequestParam(required = false) List<Long> kbIds,
            @RequestParam(defaultValue = "10") int topK
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        Long tenantId = (Long) httpRequest.getAttribute("tenantId");
        String tenantIdStr = tenantId != null ? String.valueOf(tenantId) : "";
        String userIdStr = userId != null ? String.valueOf(userId) : "";

        // 调用 Python AI 服务搜索
        Map<String, Object> aiResult = aiServiceClient.globalSearch(
                query, tenantIdStr, userIdStr, kbIds, topK
        );

        List<Map<String, Object>> documents = (List<Map<String, Object>>) aiResult.getOrDefault("documents", List.of());
        List<Map<String, Object>> messages = (List<Map<String, Object>>) aiResult.getOrDefault("messages", List.of());

        // ES 消息搜索结果为空时，回退到 PG ILIKE
        if (messages.isEmpty()) {
            messages = searchMessagesFromPg(query, userId, tenantId, topK);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", documents);
        result.put("messages", messages);
        return ResultUtils.success(result);
    }

    /**
     * PG ILIKE 消息搜索兜底（ES 不可用时使用）。
     */
    private List<Map<String, Object>> searchMessagesFromPg(String query, Long userId, Long tenantId, int topK) {
        // 先查该用户的会话 ID 列表
        QueryWrapper<Conversation> convQuery = new QueryWrapper<>();
        convQuery.eq("user_id", userId);
        convQuery.select("id", "title");
        List<Conversation> convs = conversationMapper.selectList(convQuery);
        if (convs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> convTitleMap = convs.stream()
                .collect(Collectors.toMap(Conversation::getId, Conversation::getTitle, (a, b) -> a));

        // ILIKE 搜索消息
        QueryWrapper<Message> msgQuery = new QueryWrapper<>();
        msgQuery.in("conversation_id", convTitleMap.keySet());
        msgQuery.like("content", query);
        msgQuery.orderByDesc("create_time");
        msgQuery.last("LIMIT " + topK);
        List<Message> msgs = messageMapper.selectList(msgQuery);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Message m : msgs) {
            String convTitle = convTitleMap.getOrDefault(m.getConversationId(), "未知会话");
            String highlight = highlightContent(m.getContent(), query);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(m.getId()));
            item.put("conversation_id", String.valueOf(m.getConversationId()));
            item.put("conversation_title", convTitle);
            item.put("role", m.getRole());
            item.put("content", m.getContent().length() > 200 ? m.getContent().substring(0, 200) : m.getContent());
            item.put("highlight", highlight);
            item.put("score", 0.0);
            result.add(item);
        }
        return result;
    }

    /** 简单高亮：把匹配关键词包在 <em> 标签中（大小写不敏感）。 */
    private String highlightContent(String content, String query) {
        if (content == null || query == null) return "";
        String lowerContent = content.toLowerCase();
        String lowerQuery = query.toLowerCase();
        int idx = lowerContent.indexOf(lowerQuery);
        if (idx < 0) return "";
        int start = Math.max(0, idx - 30);
        int end = Math.min(content.length(), idx + query.length() + 120);
        String snippet = content.substring(start, end);
        // 在 snippet 中高亮关键词
        int relIdx = lowerContent.indexOf(lowerQuery, start) - start;
        if (relIdx >= 0 && relIdx + query.length() <= snippet.length()) {
            String before = snippet.substring(0, relIdx);
            String match = snippet.substring(relIdx, relIdx + query.length());
            String after = snippet.substring(relIdx + query.length());
            return (start > 0 ? "..." : "") + before + "<em>" + match + "</em>" + after + (end < content.length() ? "..." : "");
        }
        return (start > 0 ? "..." : "") + snippet + (end < content.length() ? "..." : "");
    }
}
