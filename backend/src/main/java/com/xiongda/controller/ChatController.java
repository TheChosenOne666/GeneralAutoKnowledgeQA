package com.xiongda.controller;

import com.xiongda.client.AiServiceClient;
import com.xiongda.dto.chat.ChatDto.*;
import com.xiongda.entity.Conversation;
import com.xiongda.entity.Message;
import com.xiongda.repository.ConversationRepository;
import com.xiongda.repository.MessageRepository;
import com.xiongda.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 问答控制器 — 会话管理 + SSE 流式问答。
 *
 * <p>SSE 流程：前端 → Java（SSE 透传）→ Python AI 服务（RAG + LangChain）
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AiServiceClient aiServiceClient;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationOut>> listConversations() {
        var userId = securityContextUtil.getCurrentUserId();
        var convs = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        var result = convs.stream().map(c -> ConversationOut.builder()
                .id(c.getId())
                .title(c.getTitle())
                .messageCount(c.getMessageCount())
                .createdAt(c.getCreatedAt().toString())
                .updatedAt(c.getUpdatedAt().toString())
                .build()
        ).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/conversations/{convId}/messages")
    public ResponseEntity<List<MessageOut>> getMessages(@PathVariable UUID convId) {
        var messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(convId);
        var result = messages.stream().map(m -> MessageOut.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .sources(m.getSources() != null ? m.getSources().stream().map(o -> (Object) o).toList() : null)
                .model(m.getModel())
                .createdAt(m.getCreatedAt().toString())
                .build()
        ).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * SSE 流式问答 — 透传 Python AI 服务的流式响应。
     */
    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest req) {
        var userId = securityContextUtil.getCurrentUserId();
        var tenantId = securityContextUtil.getCurrentTenantId();

        // 创建会话（如果未指定）
        UUID convId = req.getConversationId();
        if (convId == null) {
            var conv = Conversation.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .title(req.getContent().length() > 50 ? req.getContent().substring(0, 50) : req.getContent())
                    .build();
            conversationRepository.save(conv);
            convId = conv.getId();
        }

        // 保存用户消息
        var userMsg = Message.builder()
                .conversationId(convId)
                .role("user")
                .content(req.getContent())
                .build();
        messageRepository.save(userMsg);

        // 调用 Python AI 服务，透传 SSE 流
        return aiServiceClient.chatStream(
                req.getContent(),
                convId,
                req.getKbIds(),
                req.getModel(),
                req.getMode(),
                tenantId
        );
    }
}
