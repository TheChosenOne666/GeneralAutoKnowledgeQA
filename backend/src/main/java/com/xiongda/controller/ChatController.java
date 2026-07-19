package com.xiongda.controller;

import com.xiongda.client.AiServiceClient;
import com.xiongda.common.BaseResponse;
import com.xiongda.common.ResultUtils;
import com.xiongda.model.dto.chat.ChatRequest;
import com.xiongda.model.dto.chat.RenameConversationRequest;
import com.xiongda.model.entity.ChatAttachment;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.ConversationVO;
import com.xiongda.model.vo.MessageVO;
import com.xiongda.service.AiConfigService;
import com.xiongda.service.ChatAttachmentService;
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

    @Resource
    private ChatAttachmentService chatAttachmentService;

    @Resource
    private com.xiongda.service.TenantService tenantService;

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
    public BaseResponse<List<MessageVO>> listMessages(@RequestParam Long conversationId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        // 归属校验：仅允许读取当前用户自己的会话消息（防越权/跨账号串号）
        List<MessageVO> list = chatService.listMessages(conversationId, loginUser.getId());
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

        // M5-9 多模态问答：把 imageIds / attachmentIds 解析为文件绝对路径透传给 Python
        List<String> imagePaths = resolveAttachmentPaths(req.getImageIds(), loginUser.getTenantId(), "image");
        List<String> attachmentPaths = resolveAttachmentPaths(req.getAttachmentIds(), loginUser.getTenantId(), "attachment");

        // M6-1：获取租户级检索配置透传给 Python（NULL 走 Python settings 默认值）
        String retrievalConfig = tenantService.getRetrievalConfig(loginUser.getTenantId());

        Flux<DataBuffer> upstream = aiServiceClient.chatStream(
                req.getContent(),
                convId,
                req.getKbIds(),
                req.getModel(),
                req.getMode(),
                loginUser.getTenantId(),
                req.getHistory(),
                aiConfig,
                imagePaths,
                attachmentPaths,
                retrievalConfig
        );

        StringBuilder rawBuf = new StringBuilder();
        StringBuilder answerBuilder = new StringBuilder();
        AtomicReference<String> sourcesRef = new AtomicReference<>("");
        StringBuilder frameBuf = new StringBuilder();
        // 流式 UTF-8 解码器：跨 DataBuffer 边界的不完整多字节字符会被缓存，
        // 待后续字节补齐后再正确解码，避免分块边界截断中文产生 U+FFFD 替换符。
        // onMalformedInput/onUnmappableCharacter 设为 REPLACE 仅作兜底（真正非法字节才替换），
        // 正常跨 buffer 的不完整序列在 endOfInput=false 下会被缓存而非判为非法。
        CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        return upstream
                .map(db -> {
                    ByteBuffer bb = db.toByteBuffer();
                    // 三参数 decode(endOfInput=false) 才是流式：跨 DataBuffer 边界的
                    // 不完整多字节字符被缓存在解码器内，待后续字节补齐后再输出，
                    // 避免分块边界截断中文产生 U+FFFD 替换符。
                    // （单参数 decode(ByteBuffer) 内部 endOfInput=true，会把边界处
                    //  不完整字节直接 REPLACE 成 U+FFFD，因此此处必须用三参数。）
                    CharBuffer out = CharBuffer.allocate(bb.remaining());
                    utf8Decoder.decode(bb, out, false);
                    out.flip();
                    DataBufferUtils.release(db);
                    return out.toString();
                })
                .concatMap(chunk -> {
                    // 将 Python 原始 SSE 按事件帧（\n\n 分隔）切分，逐帧转译为标准 SSE 推给前端，
                    // 保留 event 类型（thinking/token/sources/done），未成帧的尾部留在 frameBuf 等下一片。
                    rawBuf.append(chunk);
                    frameBuf.append(chunk);
                    List<String> frames = drainCompleteFrames(frameBuf);
                    return frames.isEmpty() ? Flux.empty() : Flux.fromIterable(frames);
                })
                .doOnTerminate(() -> {
                    // flush 流式解码器尾部：流结束时若有未补全的多字节字符一并解出
                    try {
                        CharBuffer tail = utf8Decoder.decode(ByteBuffer.allocate(0));
                        if (tail.length() > 0) {
                            rawBuf.append(tail.toString());
                        }
                    } catch (Exception e) {
                        log.warn("flush 解码尾部失败: {}", e.getMessage());
                    }
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

    /**
     * 解析问答请求中携带的附件 ID 列表为文件绝对路径列表（带租户隔离 + 分类校验）。
     *
     * <p>M5-9 多模态问答：前端传 imageIds / attachmentIds，Java 解析为路径透传给 Python。
     * 不存在的 ID 或跨租户的记录会被静默跳过（不抛错，避免阻塞主问答流程）。</p>
     *
     * @param ids       附件 ID 列表（可能为 null 或空）
     * @param tenantId  当前租户 ID
     * @param category  限定分类（image / attachment），与请求字段对应，防止前端把 attachment 当 image 传
     * @return 文件绝对路径列表（保持 ids 顺序，过滤掉无效项）
     */
    private List<String> resolveAttachmentPaths(List<Long> ids, Long tenantId, String category) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ChatAttachment> attachments = chatAttachmentService.listByIdsAndTenant(ids, tenantId, category);
        List<String> paths = new ArrayList<>();
        for (ChatAttachment a : attachments) {
            if (a.getFilePath() != null && !a.getFilePath().isBlank()) {
                paths.add(a.getFilePath());
            }
        }
        return paths;
    }
}
