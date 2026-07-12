package com.xiongda.controller;

import com.xiongda.client.AiServiceClient;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.ResultUtils;
import com.xiongda.model.dto.chat.ChatRequest;
import com.xiongda.model.dto.chat.RenameConversationRequest;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.ConversationVO;
import com.xiongda.model.vo.MessageVO;
import com.xiongda.service.AiConfigService;
import com.xiongda.service.ChatService;
import com.xiongda.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 问答控制器 — 会话管理 + SSE 流式问答。
 *
 * <p>SSE 流程：前端 → Java（SSE 透传）→ Python AI 服务（RAG + LangChain）
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/chat")
@Slf4j
public class ChatController {

    @Resource
    private ChatService chatService;

    @Resource
    private AiServiceClient aiServiceClient;

    @Resource
    private UserService userService;

    @Resource
    private AiConfigService aiConfigService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     * 重命名会话（校验归属）。
     */
    @PostMapping("/conversation/rename")
    public BaseResponse<Boolean> renameConversation(@RequestBody RenameConversationRequest req, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        chatService.renameConversation(req.getId(), loginUser.getId(), req.getTitle());
        return ResultUtils.success(true);
    }

    /**
     * 删除会话（含其消息，校验归属）。
     */
    @PostMapping("/conversation/delete")
    public BaseResponse<Boolean> deleteConversation(@RequestParam Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        chatService.deleteConversation(id, loginUser.getId());
        return ResultUtils.success(true);
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
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest req, HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);

        // 创建会话（如果未指定）
        final Long convId;
        Long requestedId = req.getConversationId();
        if (requestedId == null) {
            String title = req.getContent().length() > 50 ? req.getContent().substring(0, 50) : req.getContent();
            convId = chatService.createConversation(loginUser.getTenantId(), loginUser.getId(), title);
        } else {
            convId = requestedId;
        }

        // 保存用户消息（先写 L3 缓存再落库）
        chatService.saveUserMessage(convId, req.getContent());

        // 调用 Python AI 服务：将原始 SSE 按事件帧切分后重新包装成标准 SSE（event:/data: 行）逐帧推给前端，
        // 同时聚合助手回答落库。
        // 注意：produces 必须为 TEXT_PLAIN。若用 TEXT_EVENT_STREAM，Spring MVC 会把 Flux<String> 的每个
        // 元素自动加 "data: " 前缀二次包装，破坏手写 event: 行。前端用 fetch+reader 手动按行解析，Content-Type
        // 不影响解析。
        // M3-3：拉取用户在界面配置的 AI 模型（含 API Key）透传给 Python，供其真正消费并识别模型配置错误。
        com.xiongda.model.entity.AiConfig rawConfig =
                aiConfigService.getRawConfig(loginUser.getTenantId(), loginUser.getId());
        log.info("[M3-3诊断] 解析AI配置 tenantId={} userId={} rawConfigIsNull={}",
                loginUser.getTenantId(), loginUser.getId(), rawConfig == null);
        Map<String, Object> aiConfig = AiServiceClient.toAiConfigMap(rawConfig);
        log.info("[M3-3诊断] 透传给Python的ai_config={}",
                aiConfig == null ? "NULL(走env兜底)" : aiConfig.keySet());
        Flux<DataBuffer> upstream = aiServiceClient.chatStream(
                req.getContent(),
                convId,
                req.getKbIds(),
                req.getModel(),
                req.getMode(),
                loginUser.getTenantId(),
                req.getHistory(),
                aiConfig
        );

        StringBuilder rawBuf = new StringBuilder();
        StringBuilder answerBuilder = new StringBuilder();
        AtomicReference<String> sourcesRef = new AtomicReference<>("");
        StringBuilder frameBuf = new StringBuilder();

        return upstream
                .doOnNext(db -> rawBuf.append(decode(db)))
                .map(db -> {
                    String s = decode(db);
                    DataBufferUtils.release(db);
                    return s;
                })
                .concatMap(chunk -> {
                    // 将 Python 原始 SSE 按事件帧（\n\n 分隔）切分，逐帧转译为标准 SSE 推给前端，
                    // 保留 event 类型（thinking/token/sources/done），未成帧的尾部留在 frameBuf 等下一片。
                    frameBuf.append(chunk);
                    List<String> frames = drainCompleteFrames(frameBuf);
                    return frames.isEmpty() ? Flux.empty() : Flux.fromIterable(frames);
                })
                .doOnTerminate(() -> {
                    parseSseRaw(rawBuf.toString(), answerBuilder, sourcesRef);
                    String answer = answerBuilder.toString();
                    if (answer.isEmpty()) {
                        return;
                    }
                    // saveAssistantMessage 含阻塞式 JDBC + Redis 写入，必须脱离响应式
                    // Netty 事件循环线程，否则会阻塞 I/O 线程且异常易被响应式链吞掉。
                    // 卸载到 boundedElastic 异步执行，并保证异常可见、不被静默吞掉。
                    Schedulers.boundedElastic().schedule(() -> {
                        try {
                            chatService.saveAssistantMessage(convId, answer, req.getModel(), sourcesRef.get());
                        } catch (Exception e) {
                            log.error("保存助手回答失败 convId={}: {}", convId, e.getMessage(), e);
                        }
                    });
                });
    }

    /**
     * 将 DataBuffer 的可读字节解码为字符串（不消费读指针，供透传与聚合共用）。
     */
    private static String decode(DataBuffer db) {
        return StandardCharsets.UTF_8.decode(db.toByteBuffer()).toString();
    }

    /**
     * 从原始 SSE 文本（多事件拼接）中解析 token 累积为回答、done 事件提取 sources。
     * 按行解析，独立于底层分帧，兼容 WebClient 任意分块粒度。
     */
    private void parseSseRaw(String raw, StringBuilder answerBuilder, AtomicReference<String> sourcesRef) {
        String[] lines = raw.split("\n");
        String currentEvent = null;
        StringBuilder dataAcc = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("event:")) {
                flushSseData(dataAcc, currentEvent, answerBuilder, sourcesRef);
                currentEvent = line.substring(6).trim();
                dataAcc.setLength(0);
            } else if (line.startsWith("data:")) {
                if (dataAcc.length() > 0) {
                    dataAcc.append("\n");
                }
                dataAcc.append(line.substring(5).trim());
            } else if (line.trim().isEmpty()) {
                flushSseData(dataAcc, currentEvent, answerBuilder, sourcesRef);
                currentEvent = null;
                dataAcc.setLength(0);
            }
        }
        flushSseData(dataAcc, currentEvent, answerBuilder, sourcesRef);
    }

    private void flushSseData(StringBuilder dataAcc, String event,
                              StringBuilder answerBuilder, AtomicReference<String> sourcesRef) {
        if (dataAcc.length() == 0 || event == null) {
            return;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(dataAcc.toString());
            if ("token".equals(event)) {
                JsonNode content = node.get("content");
                if (content != null) {
                    answerBuilder.append(content.asText());
                }
            } else if ("done".equals(event)) {
                JsonNode sources = node.get("sources");
                if (sources != null && !sources.isNull()) {
                    sourcesRef.set(sources.toString());
                }
            }
        } catch (Exception e) {
            log.warn("解析 SSE 数据失败，忽略: {}", e.getMessage());
        }
    }

    /**
     * 从累积缓冲中切出所有已成帧（以空行 \n\n 结尾）的 SSE 事件并转译为标准 SSE 文本，
     * 未成帧的尾部留在 buffer 中等待后续分片。
     */
    private List<String> drainCompleteFrames(StringBuilder buf) {
        List<String> frames = new ArrayList<>();
        int idx;
        while ((idx = buf.indexOf("\n\n")) >= 0) {
            String frame = buf.substring(0, idx);
            buf.delete(0, idx + 2);
            String translated = translateFrame(frame);
            if (translated != null) {
                frames.add(translated);
            }
        }
        return frames;
    }

    /**
     * 将单个 SSE 事件帧（event:/data: 行）转译为标准 SSE 文本：保留事件名，原样回传 data JSON。
     * 无 event 或无 data 的帧返回 null（不推送）。
     */
    private String translateFrame(String frame) {
        String eventType = null;
        StringBuilder data = new StringBuilder();
        for (String line : frame.split("\n", -1)) {
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append("\n");
                }
                data.append(line.substring(5).trim());
            }
        }
        if (eventType == null || data.length() == 0) {
            return null;
        }
        return "event: " + eventType + "\ndata: " + data + "\n\n";
    }
}
