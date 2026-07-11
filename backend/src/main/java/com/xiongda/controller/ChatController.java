package com.xiongda.controller;

import com.xiongda.client.AiServiceClient;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.ResultUtils;
import com.xiongda.model.dto.chat.ChatRequest;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.ConversationVO;
import com.xiongda.model.vo.MessageVO;
import com.xiongda.service.ChatService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 问答控制器 — 会话管理 + SSE 流式问答。
 *
 * <p>SSE 流程：前端 → Java（SSE 透传）→ Python AI 服务（RAG + LangChain）
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Resource
    private ChatService chatService;

    @Resource
    private AiServiceClient aiServiceClient;

    @Resource
    private com.xiongda.service.UserService userService;

    /**
     * 获取会话列表。
     */
    @GetMapping("/conversation/list")
    public BaseResponse<List<ConversationVO>> listConversations(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<ConversationVO> list = chatService.listConversations(loginUser.getId());
        return ResultUtils.success(list);
    }

    /**
     * 创建会话。
     */
    @PostMapping("/conversation/create")
    public BaseResponse<Long> createConversation(HttpServletRequest request, @RequestParam(required = false) String title) {
        User loginUser = userService.getLoginUser(request);
        Long convId = chatService.createConversation(loginUser.getTenantId(), loginUser.getId(), title);
        return ResultUtils.success(convId);
    }

    /**
     * 获取会话消息列表。
     */
    @GetMapping("/message/list")
    public BaseResponse<List<MessageVO>> listMessages(@RequestParam Long conversationId) {
        List<MessageVO> list = chatService.listMessages(conversationId);
        return ResultUtils.success(list);
    }

    /**
     * SSE 流式问答 — 透传 Python AI 服务的流式响应。
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest req, HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);

        // 创建会话（如果未指定）
        Long convId = req.getConversationId();
        if (convId == null) {
            String title = req.getContent().length() > 50 ? req.getContent().substring(0, 50) : req.getContent();
            convId = chatService.createConversation(loginUser.getTenantId(), loginUser.getId(), title);
        }

        // 保存用户消息
        chatService.saveUserMessage(convId, req.getContent());

        // 调用 Python AI 服务，透传 SSE 流
        return aiServiceClient.chatStream(
                req.getContent(),
                convId,
                req.getKbIds(),
                req.getModel(),
                req.getMode(),
                loginUser.getTenantId()
        );
    }
}
