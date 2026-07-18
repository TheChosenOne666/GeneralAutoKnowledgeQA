package com.xiongda.controller;

import com.xiongda.client.AiServiceClient;
import com.xiongda.model.dto.chat.ChatRequest;
import com.xiongda.model.entity.User;
import com.xiongda.service.ChatService;
import com.xiongda.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.xiongda.model.entity.ChatAttachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatController SSE 流式问答单元测试。
 *
 * <p>验证核心缺口修复：流式响应透传给前端的同时，Java 侧聚合 token 事件的回答内容
 * 与 done 事件的 sources，并在流结束时调用 ChatService.saveAssistantMessage 落库 + 写 L3 缓存。
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private UserService userService;

    @Mock
    private com.xiongda.service.AiConfigService aiConfigService;

    @Mock
    private com.xiongda.service.ChatAttachmentService chatAttachmentService;

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController();
        ReflectionTestUtils.setField(controller, "chatService", chatService);
        ReflectionTestUtils.setField(controller, "aiServiceClient", aiServiceClient);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "aiConfigService", aiConfigService);
        ReflectionTestUtils.setField(controller, "chatAttachmentService", chatAttachmentService);
    }

    /**
     * 构造一段仿 Python AI 服务的原始 SSE 文本（event:/data: 多事件拼接），
     * 包含 thinking / token / done 事件，验证聚合与落库调用。
     */
    private static DataBuffer sseBuffer() {
        String raw = """
                event: thinking
                data: {"content": "正在思考..."}

                event: token
                data: {"content": "你好"}

                event: token
                data: {"content": "世界"}

                event: done
                data: {"conversation_id": "1", "sources": [{"source": "a.pdf", "page": 1, "content": "x"}]}

                """;
        return new DefaultDataBufferFactory().wrap(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void chatStream_persistsAggregatedAnswerAndSources() {
        User user = new User();
        user.setId(7L);
        user.setTenantId(3L);
        when(userService.getLoginUser(any())).thenReturn(user);
        when(chatService.createConversation(3L, 7L, "你好吗")).thenReturn(1L);
        when(aiServiceClient.chatStream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(sseBuffer()));

        ChatRequest req = new ChatRequest();
        req.setContent("你好吗");
        req.setModel("doubao");

        // 消费返回的 Flux，触发 doOnTerminate（聚合 + 异步落库）
        List<String> streamed = controller.chatStream(req, null).collectList().block();
        // 标准 SSE 转译后每个完整事件为一帧：thinking / token / token / done
        assertEquals(4, streamed.size());
        String joined = String.join("", streamed);
        // 前端收到的应是带 event: 的标准 SSE，而非裸 JSON 碎片
        org.junit.jupiter.api.Assertions.assertTrue(joined.contains("event: token"));
        org.junit.jupiter.api.Assertions.assertTrue(joined.contains("event: done"));
        org.junit.jupiter.api.Assertions.assertTrue(joined.contains("\"你好\""));
        org.junit.jupiter.api.Assertions.assertTrue(joined.contains("\"世界\""));

        // 流结束后，异步（boundedElastic）调用 saveAssistantMessage，需等待
        verify(chatService, timeout(3000)).saveAssistantMessage(
                eq(1L), eq("你好世界"), eq("doubao"), contains("a.pdf"));
    }

    @Test
    void chatStream_emptyAnswer_skipsPersist() {
        User user = new User();
        user.setId(7L);
        user.setTenantId(3L);
        when(userService.getLoginUser(any())).thenReturn(user);
        when(chatService.createConversation(3L, 7L, "空回答")).thenReturn(2L);
        // 只推 thinking 事件、无 token，回答聚合结果为空
        String raw = "event: thinking\ndata: {\"content\": \"正在思考...\"}\n\n";
        DataBuffer db = new DefaultDataBufferFactory().wrap(raw.getBytes(StandardCharsets.UTF_8));
        when(aiServiceClient.chatStream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(db));

        ChatRequest req = new ChatRequest();
        req.setContent("空回答");

        controller.chatStream(req, null).collectList().block();

        // 空回答不应落库
        verify(chatService, org.mockito.Mockito.never()).saveAssistantMessage(any(), any(), any(), any());
        DataBufferUtils.release(db);
    }

    /**
     * 回归测试（2026-07-16）：AI 流在中文多字节字符（如「第」U+7B2C，UTF-8 三字节）中间被分块时，
     * Java 侧不应产生 U+FFFD 替换符或字符损坏。模拟 HTTP chunk 边界恰好切在多字节字符中间的场景，
     * 验证流式 CharsetDecoder 能缓存不完整尾部、待后续字节补齐后正确解码。
     */
    @Test
    void chatStream_multibyteCharSplitAcrossBuffers_preserved() {
        User user = new User();
        user.setId(7L);
        user.setTenantId(3L);
        when(userService.getLoginUser(any())).thenReturn(user);
        when(chatService.createConversation(3L, 7L, "测试")).thenReturn(9L);

        // token 内容含「第一时间」（其中「第」为三字节 UTF-8），done 事件收尾
        String raw = "event: token\ndata: {\"content\": \"第一时间修改初始密码\"}\n\n"
                + "event: done\ndata: {\"conversation_id\": \"9\", \"sources\": []}\n\n";
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        // 精确定位「第」(UTF-8: E7 AC 8C) 首字节，在其后 1 字节处切分，
        // 使该 3 字节字符被拆到两个 DataBuffer（模拟 HTTP chunk 边界截断多字节字符）。
        int split = -1;
        for (int i = 0; i + 2 < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == 0xE7 && (bytes[i + 1] & 0xFF) == 0xAC && (bytes[i + 2] & 0xFF) == 0x8C) {
                split = i;
                break;
            }
        }
        int byteSplit = split + 1;
        DataBuffer db1 = new DefaultDataBufferFactory().wrap(java.util.Arrays.copyOfRange(bytes, 0, byteSplit));
        DataBuffer db2 = new DefaultDataBufferFactory().wrap(java.util.Arrays.copyOfRange(bytes, byteSplit, bytes.length));

        when(aiServiceClient.chatStream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(db1, db2));

        ChatRequest req = new ChatRequest();
        req.setContent("测试");
        req.setModel("doubao");

        controller.chatStream(req, null).collectList().block();

        // 修复后「第」应完整保留，answer 不含 U+FFFD；旧逻辑会因边界截断产生乱码或丢字
        verify(chatService, timeout(3000)).saveAssistantMessage(
                eq(9L), eq("第一时间修改初始密码"), eq("doubao"), any());
    }

    /**
     * M5-9 多模态问答透传测试：前端传 imageIds / attachmentIds，
     * ChatController 应调用 chatAttachmentService.listByIdsAndTenant 解析路径，
     * 并将文件路径透传给 aiServiceClient.chatStream。
     */
    @Test
    void chatStream_resolvesAttachmentPaths_andForwardsToAiService() {
        User user = new User();
        user.setId(7L);
        user.setTenantId(3L);
        when(userService.getLoginUser(any())).thenReturn(user);
        when(chatService.createConversation(3L, 7L, "看这张图")).thenReturn(10L);

        // 模拟 chatAttachmentService 返回两个附件（一个图片、一个文档）
        ChatAttachment imgAtt = new ChatAttachment();
        imgAtt.setId(100L);
        imgAtt.setTenantId(3L);
        imgAtt.setCategory("image");
        imgAtt.setFilePath("/uploads/3/chat-attachments/image/abc.png");

        ChatAttachment docAtt = new ChatAttachment();
        docAtt.setId(200L);
        docAtt.setTenantId(3L);
        docAtt.setCategory("attachment");
        docAtt.setFilePath("/uploads/3/chat-attachments/attachment/xyz.pdf");

        when(chatAttachmentService.listByIdsAndTenant(eq(List.of(100L)), eq(3L), eq("image")))
                .thenReturn(List.of(imgAtt));
        when(chatAttachmentService.listByIdsAndTenant(eq(List.of(200L)), eq(3L), eq("attachment")))
                .thenReturn(List.of(docAtt));

        String raw = "event: token\ndata: {\"content\": \"这是一张图\"}\n\n"
                + "event: done\ndata: {\"conversation_id\": \"10\", \"sources\": []}\n\n";
        DataBuffer db = new DefaultDataBufferFactory().wrap(raw.getBytes(StandardCharsets.UTF_8));

        when(aiServiceClient.chatStream(any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.argThat(list -> list != null && !list.isEmpty() && list.get(0).equals("/uploads/3/chat-attachments/image/abc.png")),
                org.mockito.ArgumentMatchers.argThat(list -> list != null && !list.isEmpty() && list.get(0).equals("/uploads/3/chat-attachments/attachment/xyz.pdf"))
        )).thenReturn(Flux.just(db));

        ChatRequest req = new ChatRequest();
        req.setContent("看这张图");
        req.setModel("gpt-4o");
        req.setImageIds(List.of(100L));
        req.setAttachmentIds(List.of(200L));

        controller.chatStream(req, null).collectList().block();

        // 验证 chatAttachmentService 被调用了两次（一次 image、一次 attachment）
        verify(chatAttachmentService).listByIdsAndTenant(eq(List.of(100L)), eq(3L), eq("image"));
        verify(chatAttachmentService).listByIdsAndTenant(eq(List.of(200L)), eq(3L), eq("attachment"));

        // 验证路径透传给了 aiServiceClient（通过 argThat 匹配器已隐式验证）
        // 验证回答落库
        verify(chatService, timeout(3000)).saveAssistantMessage(
                eq(10L), eq("这是一张图"), eq("gpt-4o"), any());
    }

    /**
     * M5-9 降级测试：imageIds / attachmentIds 为空时不应调用 chatAttachmentService，
     * 透传给 aiServiceClient 的路径列表应为空。
     */
    @Test
    void chatStream_noAttachments_doesNotResolvePaths() {
        User user = new User();
        user.setId(7L);
        user.setTenantId(3L);
        when(userService.getLoginUser(any())).thenReturn(user);
        when(chatService.createConversation(3L, 7L, "普通问题")).thenReturn(11L);

        String raw = "event: token\ndata: {\"content\": \"回答\"}\n\n"
                + "event: done\ndata: {\"conversation_id\": \"11\", \"sources\": []}\n\n";
        DataBuffer db = new DefaultDataBufferFactory().wrap(raw.getBytes(StandardCharsets.UTF_8));

        when(aiServiceClient.chatStream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(db));

        ChatRequest req = new ChatRequest();
        req.setContent("普通问题");

        controller.chatStream(req, null).collectList().block();

        // 没有附件时不应调用 chatAttachmentService
        verify(chatAttachmentService, org.mockito.Mockito.never()).listByIdsAndTenant(any(), any(), any());
        verify(chatService, timeout(3000)).saveAssistantMessage(
                eq(11L), eq("回答"), any(), any());
    }
}
