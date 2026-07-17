package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.annotation.AuditLog;
import com.xiongda.client.AiServiceClient;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.common.ErrorCode;
import com.xiongda.mapper.DocumentMapper;
import com.xiongda.mapper.TenantMapper;
import com.xiongda.model.entity.AiConfig;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.DocumentVO;
import com.xiongda.model.vo.PageContentVO;
import com.xiongda.service.AiConfigService;
import com.xiongda.service.DocumentService;
import com.xiongda.service.KbPermission;
import com.xiongda.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements DocumentService {

    @Resource
    private AiServiceClient aiServiceClient;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private TenantMapper tenantMapper;

    @Override
    @AuditLog(action = "doc_upload", resourceType = "document")
    public Long uploadDocument(Long kbId, Long tenantId, User user, String filename, String fileType,
                               Long fileSize, String filePath) {
        // 知识库写权限：租户隔离（第一维度）+ 共享库仅租户管理员 / 个人库仅 owner
        KnowledgeBase kb = knowledgeBaseService.getById(kbId);
        KbPermission.assertCanWrite(kb, user.getId(), tenantId, user.getRole());

        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setTenantId(tenantId);
        doc.setFilename(filename);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setFilePath(filePath);
        doc.setStatus("processing");
        doc.setChunkCount(0);
        doc.setUploadedBy(user.getId());

        // 租户文档数配额校验（对标业界成熟方案：达到上限即拒绝，<=0 视为不限）
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant != null && tenant.getMaxDocuments() != null && tenant.getMaxDocuments() > 0) {
            long docCount = this.count(new QueryWrapper<Document>().eq("tenant_id", tenantId));
            ThrowUtils.throwIf(docCount >= tenant.getMaxDocuments(),
                    ErrorCode.OPERATION_ERROR, "租户文档数已达上限");
        }

        this.save(doc);
        // 知识库文档数同步（重新统计，自我校正已有偏差）
        syncKbDocCount(kbId, tenantId);
        return doc.getId();
    }

    @Override
    public List<DocumentVO> listDocuments(Long kbId, Long tenantId) {
        QueryWrapper<Document> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("kb_id", kbId).eq("tenant_id", tenantId);
        queryWrapper.orderByDesc("create_time");
        List<Document> docs = this.list(queryWrapper);
        return docs.stream().map(this::getDocumentVO).toList();
    }

    @Override
    @AuditLog(action = "doc_delete", resourceType = "document")
    public boolean deleteDocument(Long docId, Long tenantId, User user) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR);
        // 知识库写权限：租户隔离（第一维度）+ 共享库仅租户管理员 / 个人库仅 owner
        KnowledgeBase kb = knowledgeBaseService.getById(doc.getKbId());
        KbPermission.assertCanWrite(kb, user.getId(), tenantId, user.getRole());
        // 同步清理 Python 侧向量并取消可能正在排队的问答增强任务（对标业界成熟方案 任务取消）
        try {
            aiServiceClient.deleteDocument(docId);
        } catch (Exception e) {
            log.warn("清理文档向量/增强任务失败 docId={} : {}", docId, e.getMessage());
        }
        // 文档删除同样改变检索结果，清该租户 L1 检索缓存
        aiServiceClient.invalidateCache(tenantId);
        boolean removed = this.removeById(docId);
        // 知识库文档数同步
        syncKbDocCount(doc.getKbId(), tenantId);
        return removed;
    }

    @Override
    @AuditLog(action = "doc_batch_delete", resourceType = "document")
    public int deleteDocuments(List<Long> docIds, Long tenantId, User user) {
        ThrowUtils.throwIf(docIds == null || docIds.isEmpty(), ErrorCode.PARAMS_ERROR, "文档 ID 列表为空");
        // 去重，保持稳定顺序
        List<Long> ids = docIds.stream().distinct().toList();

        // 第一遍：全部校验（存在 / 租户隔离 / 写权限），任一失败即抛异常、不删除任何文档
        List<Document> docs = new ArrayList<>(ids.size());
        for (Long docId : ids) {
            Document doc = this.getById(docId);
            ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在: " + docId);
            ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR);
            KnowledgeBase kb = knowledgeBaseService.getById(doc.getKbId());
            KbPermission.assertCanWrite(kb, user.getId(), tenantId, user.getRole());
            docs.add(doc);
        }

        // 第二遍：逐个清理 Python 侧向量并逻辑删除；收集涉及的知识库以便统一同步文档数
        Set<Long> affectedKbIds = new HashSet<>();
        int removed = 0;
        for (Document doc : docs) {
            try {
                aiServiceClient.deleteDocument(doc.getId());
            } catch (Exception e) {
                log.warn("清理文档向量/增强任务失败 docId={} : {}", doc.getId(), e.getMessage());
            }
            if (this.removeById(doc.getId())) {
                removed++;
                affectedKbIds.add(doc.getKbId());
            }
        }

        // 文档删除改变检索结果，批量删除只需清一次该租户 L1 检索缓存
        aiServiceClient.invalidateCache(tenantId);
        // 同步涉及知识库的文档数
        for (Long kbId : affectedKbIds) {
            syncKbDocCount(kbId, tenantId);
        }
        return removed;
    }

    @Override
    public boolean cancelDocument(Long docId, Long tenantId, User user) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权限取消该文档");
        // 知识库写权限：租户隔离（第一维度）+ 共享库仅租户管理员 / 个人库仅 owner
        KnowledgeBase kb = knowledgeBaseService.getById(doc.getKbId());
        KbPermission.assertCanWrite(kb, user.getId(), tenantId, user.getRole());

        // 仅非终态可取消（processing/parsing/retrieving/optimizing）；终态幂等返回
        String status = doc.getStatus();
        if ("ready".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            return true;
        }

        // 标 cancelled（终态）—— 终态守卫保证后续 Python 中间态回调不再覆盖本状态
        this.updateDocumentStatus(docId, "cancelled", null, null);

        // 通知 Python：清理已写入向量 + 取消排队的问答增强任务（对标业界成熟方案 任务取消）
        try {
            aiServiceClient.cancelDocument(docId);
        } catch (Exception e) {
            log.warn("通知 Python 取消文档处理失败 docId={} : {}", docId, e.getMessage());
        }
        // 文档状态变更同样影响检索结果，清该租户 L1 检索缓存
        try {
            aiServiceClient.invalidateCache(tenantId);
        } catch (Exception e) {
            log.warn("清检索缓存失败（取消文档）tenantId={} : {}", tenantId, e.getMessage());
        }
        return true;
    }

    @Override
    public boolean retryDocument(Long docId, Long tenantId, User user) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权限重试该文档");
        // 知识库写权限：租户隔离（第一维度）+ 共享库仅租户管理员 / 个人库仅 owner
        KnowledgeBase kb = knowledgeBaseService.getById(doc.getKbId());
        KbPermission.assertCanWrite(kb, user.getId(), tenantId, user.getRole());

        // 仅终态失败 / 已取消可重试；处理中或已就绪无需重试
        String status = doc.getStatus();
        if (!"failed".equals(status) && !"cancelled".equals(status)) {
            ThrowUtils.throwIf(true, ErrorCode.OPERATION_ERROR, "仅处理失败或已取消的文档可重试");
        }

        // 原文件已不存在则无法重试，引导重新上传（避免重试后再因缺文件失败）
        Path filePath = Path.of(doc.getFilePath());
        ThrowUtils.throwIf(!Files.exists(filePath) || !Files.isRegularFile(filePath),
                ErrorCode.OPERATION_ERROR, "原文件已被清理或删除，无法重试，请重新上传该文档");

        // 重置状态：绕过终态守卫直接置 processing，并清空错误标记 / 分块数
        doc.setStatus("processing");
        doc.setErrorMsg(null);
        doc.setModelConfigError(false);
        doc.setQuotaError(false);
        doc.setChunkCount(0);
        this.updateById(doc);

        // 复用原上传者配置重新触发处理（确保文档与上传者 AI 配置绑定一致）
        this.triggerDocumentProcessing(docId, doc.getFilePath(), doc.getFileType(),
                doc.getKbId(), tenantId, doc.getUploadedBy());
        log.info("文档重试处理已触发: docId={}, oldStatus={}", docId, status);
        return true;
    }

    @Override
    public DocumentVO getDocumentVO(Document doc) {
        if (doc == null) {
            return null;
        }
        DocumentVO vo = new DocumentVO();
        vo.setId(doc.getId());
        vo.setKbId(doc.getKbId());
        vo.setFilename(doc.getFilename());
        vo.setFileType(doc.getFileType());
        vo.setFileSize(doc.getFileSize());
        vo.setStatus(doc.getStatus());
        vo.setChunkCount(doc.getChunkCount());
        vo.setErrorMsg(doc.getErrorMsg());
        vo.setModelConfigError(doc.getModelConfigError());
        vo.setQuotaError(doc.getQuotaError());
        vo.setCreateTime(doc.getCreateTime());
        return vo;
    }

    @Override
    public boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg) {
        return updateDocumentStatus(docId, status, chunkCount, errorMsg, null);
    }

    @Override
    public boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg,
            Boolean modelConfigError) {
        // 兼容旧调用方（如内部状态回调中间态）：额度标记默认 null（不更新）
        return updateDocumentStatus(docId, status, chunkCount, errorMsg, modelConfigError, null, null);
    }

    @Override
    public boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg,
            Boolean modelConfigError, Boolean quotaError) {
        // 兼容旧调用方：content 默认 null（不更新全文）
        return updateDocumentStatus(docId, status, chunkCount, errorMsg, modelConfigError, quotaError, null);
    }

    @Override
    public boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg,
            Boolean modelConfigError, Boolean quotaError, String content) {
        Document doc = this.getById(docId);
        if (doc == null) {
            return false;
        }
        // 终态守卫（对标业界成熟方案 终态不可变）：ready/failed 落定后，忽略迟到的最终之前阶段
        // 回调（如 retrieving/optimizing），防止异步增强阶段竞态把已就绪文档回退到中间态。
        String current = doc.getStatus();
        boolean currentTerminal = "ready".equals(current) || "failed".equals(current) || "cancelled".equals(current);
        boolean nextTerminal = "ready".equals(status) || "failed".equals(status) || "cancelled".equals(status);
        if (currentTerminal && !nextTerminal) {
            log.warn("忽略非终态回调（当前已是终态）docId={}, current={}, next={}", docId, current, status);
            return false;
        }
        doc.setStatus(status);
        if (chunkCount != null) {
            doc.setChunkCount(chunkCount);
        }
        // 成功终态（ready/optimizing）强制清空错误标记，避免旧错误信息残留
        // （重试 / 取消后重新处理成功时，DB 中的历史 error_msg / model_config_error / quota_error 不应继续展示）。
        // 这些字段在实体上声明 updateStrategy=ALWAYS，确保此处置 null 能真正写入（绕过默认 NOT_NULL）。
        boolean success = "ready".equals(status) || "optimizing".equals(status);
        if (success) {
            doc.setErrorMsg(null);
            doc.setModelConfigError(false);
            doc.setQuotaError(false);
        } else if (errorMsg != null) {
            doc.setErrorMsg(errorMsg);
        }
        if (modelConfigError != null) {
            doc.setModelConfigError(modelConfigError);
        }
        if (quotaError != null) {
            doc.setQuotaError(quotaError);
        }
        // M5-1：回调携带的全文（optimizing 阶段）一并保存，替代旧同步返回落库
        if (content != null) {
            // 兜底剥离 U+FFFD 替换符与 C1 控制符，避免任何来源（含历史脏数据重存）的脏字符污染全文
            doc.setContent(stripGarbage(content));
        }
        return this.updateById(doc);
    }

    /** 去除 U+FFFD 替换符与 C1 控制符（U+007F–U+009F），避免脏字符进入文档全文。 */
    private static String stripGarbage(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\uFFFD", "").replaceAll("[\\u007F-\\u009F]", "");
    }

    @Override
    public void saveDocumentContent(Long docId, String content) {
        Document doc = this.getById(docId);
        if (doc == null) {
            return;
        }
        doc.setContent(content);
        this.updateById(doc);
    }

    @Override
    public String getDocumentContent(Long docId, Long tenantId, User user) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        // 仅同租户可读（与问答权限一致，普通成员亦可查看）
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权限查看该文档");
        return doc.getContent();
    }

    /** 与 ai-service/services/document_processor.py 的 CHARS_PER_PAGE 保持一致（降级估算分页用）。*/
    private static final int CHARS_PER_PAGE = 1500;

    /**
     * 文档处理异步线程池（替代 CompletableFuture.runAsync 默认的 ForkJoinPool.commonPool()）。
     *
     * <p>文档处理为 IO 密集型（等待 Python 向量化返回，单任务可达分钟级），使用专用线程池：
     * ① 避免占用公共 ForkJoinPool（影响其他并行任务）；② 队列满时 CallerRunsPolicy 由调用线程
     * （上传请求线程）兜底执行，形成背压、不丢任务也不无限堆积；③ daemon 线程 + allowCoreThreadTimeOut
     * 避免空闲常驻。核心/最大线程数与队列深度可按并发上传量调整。</p>
     */
    private static final ExecutorService DOC_PROCESS_EXECUTOR = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16),
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "doc-process-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    static {
        // 允许核心线程空闲超时回收，避免无上传时仍常驻 2 个线程
        ((ThreadPoolExecutor) DOC_PROCESS_EXECUTOR).allowCoreThreadTimeOut(true);
    }

    @Override
    public List<PageContentVO> getDocumentPages(Long docId, Long tenantId, User user) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权限查看该文档");

        // 1) 优先：AI 服务真实解析原文件（PDF 真实页码 / docx/txt/md 估算页码，与引用来源一致）
        List<Map<String, Object>> pages = aiServiceClient.extractPages(doc.getFilePath(), doc.getFileType());
        if (pages != null && !pages.isEmpty()) {
            return toPageContentVOs(pages);
        }
        // 2) 次优先：向量库已存分块重建（不依赖原文件，文档已向量化即可；
        //    修复原文件被清理 / 中文路径解析失败导致预览为空的问题，M4-4 增强）
        pages = aiServiceClient.getPagesFromDb(docId);
        if (pages != null && !pages.isEmpty()) {
            return toPageContentVOs(pages);
        }
        // 3) 降级：AI 不可用/失败，用已存全文按 CHARS_PER_PAGE 估算分页
        String content = doc.getContent();
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<PageContentVO> fallback = new ArrayList<>();
        // 按「码点」而非 UTF-16 码元切分：避免切断代理对（emoji / 生僻 CJK Ext-B）产生 U+FFFD 乱码
        int n = content.codePointCount(0, content.length());
        int idx = 0;
        int pageNo = 1;
        while (idx < n) {
            int end = Math.min(n, idx + CHARS_PER_PAGE);
            int startIdx = content.offsetByCodePoints(0, idx);
            int endIdx = content.offsetByCodePoints(0, end);
            PageContentVO vo = new PageContentVO();
            vo.setPageNo(pageNo++);
            vo.setText(content.substring(startIdx, endIdx));
            fallback.add(vo);
            idx = end;
        }
        return fallback;
    }

    /** 将 AI 服务返回的 {page_no, text} 列表转为 PageContentVO 列表（page_no 缺失时顺序补位）。*/
    private List<PageContentVO> toPageContentVOs(List<Map<String, Object>> pages) {
        List<PageContentVO> result = new ArrayList<>(pages.size());
        int fallbackNo = 1;
        for (Map<String, Object> p : pages) {
            PageContentVO vo = new PageContentVO();
            Object pageNo = p.get("page_no");
            vo.setPageNo(pageNo instanceof Number ? ((Number) pageNo).intValue() : fallbackNo);
            Object text = p.get("text");
            vo.setText(text == null ? "" : text.toString());
            result.add(vo);
            fallbackNo++;
        }
        return result;
    }

    /**
     * 重新统计知识库文档数（逻辑删除自动过滤），并写回 knowledge_base.document_count。
     */
    private void syncKbDocCount(Long kbId, Long tenantId) {
        QueryWrapper<Document> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("kb_id", kbId).eq("tenant_id", tenantId);
        long cnt = this.count(queryWrapper);
        KnowledgeBase kb = knowledgeBaseService.getById(kbId);
        if (kb != null) {
            kb.setDocumentCount((int) cnt);
            knowledgeBaseService.updateById(kb);
        }
    }

    @Override
    public void triggerDocumentProcessing(Long docId, String filePath, String fileType, Long kbId, Long tenantId,
            Long userId) {
        // M3-3：按上传用户解析其 AI 模型配置（用户级 > 租户级），透传给 Python 真正消费。
        AiConfig rawConfig = aiConfigService.getRawConfig(tenantId, userId);
        Map<String, Object> aiConfig = AiServiceClient.toAiConfigMap(rawConfig);
        log.info("[文档诊断] 触发文档处理 docId={} kbId={} tenantId={} userId={} rawConfigIsNull={} aiConfigKeys={}",
                docId, kbId, tenantId, userId, rawConfig == null,
                aiConfig == null ? "NULL" : aiConfig.keySet());
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 更新状态为解析中（M5-1 起，后续状态完全由 Python worker 经状态回调推进）
                this.updateDocumentStatus(docId, "parsing", null, null);
                log.info("[文档诊断] 已置parsing，开始调用Python入队 docId={}", docId);

                // 2. 调用 Python AI 服务：仅将主流程任务 enqueue，立即返回 processing（M5-1 异步化）。
                //    真正的提取→分块→向量化→存储由 Python 常驻 worker 异步执行、崩溃可恢复；
                //    状态经 /api/internal/document/status 回调推进（retrieving/optimizing/ready/failed），
                //    全文 content 也在 optimizing 回调中回填，Java 不再依赖本接口同步返回。
                long t0 = System.currentTimeMillis();
                Map<String, Object> result = aiServiceClient.processDocument(
                        docId, filePath, fileType, kbId, tenantId, aiConfig);
                log.info("[文档诊断] Python入队返回 docId={} 耗时={}ms result={}",
                        docId, System.currentTimeMillis() - t0, result);

                // 3. 仅处理「入队失败」：Python 入队异常时返回 failed，直接落库失败态；
                //    返回 processing 表示已入队，后续状态由回调驱动，此处无需再处理。
                String status = result.get("status") != null ? result.get("status").toString() : "failed";
                if (!"processing".equals(status)) {
                    String errorType = result.get("error_type") != null ? result.get("error_type").toString() : null;
                    String error = result.get("error") != null ? result.get("error").toString() : "未知错误";
                    // M3-3：模型配置错误（无 Key / Key 错 / 维度不匹配）标记，前端引导重配；
                    // 额度 / 限流标记，前端引导重试 / 检查额度（而非重配）
                    boolean modelConfigError = "MODEL_CONFIG_ERROR".equals(errorType);
                    boolean quotaError = "MODEL_QUOTA_ERROR".equals(errorType);
                    this.updateDocumentStatus(docId, "failed", null, error, modelConfigError, quotaError);
                    log.warn("文档入队失败: docId={}, errorType={}, error={}", docId, errorType, error);
                } else {
                    // 入队成功：文档即将可检索，清除该租户可能残留的检索缓存，
                    // 保证新文档（及其增强块）立即可被搜到，不被旧缓存误命中。
                    aiServiceClient.invalidateCache(tenantId);
                }
            } catch (Exception e) {
                log.error("文档处理触发异常: docId={}", docId, e);
                this.updateDocumentStatus(docId, "failed", null, e.getMessage());
            }
        }, DOC_PROCESS_EXECUTOR);
    }

    @Override
    public int clearFailedConfigErrorFlags(Long tenantId) {
        // 仅针对失败文档且带旧归因标记（模型配置错误 / 额度限流）的记录清零，
        // 文档保持 failed 终态，交由用户手动重试以按新配置重新归因。
        UpdateWrapper<Document> uw = new UpdateWrapper<>();
        uw.eq("status", "failed")
                .and(w -> w.eq("model_config_error", true).or().eq("quota_error", true));
        if (tenantId != null) {
            uw.eq("tenant_id", tenantId);
        }
        uw.set("model_config_error", false).set("quota_error", false);
        // getBaseMapper().update 返回影响行数（int）；entity 传 null，仅用 wrapper 的 set/where 片段
        return this.getBaseMapper().update(null, uw);
    }
}
