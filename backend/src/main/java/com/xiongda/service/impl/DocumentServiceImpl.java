package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

        // 租户文档数配额校验（对齐 WeKnora：达到上限即拒绝，<=0 视为不限）
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
        // 同步清理 Python 侧向量并取消可能正在排队的问答增强任务（对齐 WeKnora 任务取消）
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

        // 通知 Python：清理已写入向量 + 取消排队的问答增强任务（对齐 WeKnora 任务取消）
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
        Document doc = this.getById(docId);
        if (doc == null) {
            return false;
        }
        // 终态守卫（对齐 WeKnora 终态不可变）：ready/failed 落定后，忽略迟到的最终之前阶段
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
        if (errorMsg != null) {
            doc.setErrorMsg(errorMsg);
        }
        if (modelConfigError != null) {
            doc.setModelConfigError(modelConfigError);
        }
        return this.updateById(doc);
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

    @Override
    public List<PageContentVO> getDocumentPages(Long docId, Long tenantId, User user) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR, "无权限查看该文档");

        // 优先调 AI 服务真实解析（PDF 真实页码 / docx/txt/md 估算页码，与引用来源一致）
        List<Map<String, Object>> pages = aiServiceClient.extractPages(doc.getFilePath(), doc.getFileType());
        if (pages != null && !pages.isEmpty()) {
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
        // 降级：AI 不可用/失败，用已存全文按 CHARS_PER_PAGE 估算分页
        String content = doc.getContent();
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<PageContentVO> fallback = new ArrayList<>();
        int n = content.length();
        int idx = 0;
        int pageNo = 1;
        while (idx < n) {
            int end = Math.min(n, idx + CHARS_PER_PAGE);
            PageContentVO vo = new PageContentVO();
            vo.setPageNo(pageNo++);
            vo.setText(content.substring(idx, end));
            fallback.add(vo);
            idx = end;
        }
        return fallback;
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
                // 1. 更新状态为解析中
                this.updateDocumentStatus(docId, "parsing", null, null);
                log.info("[文档诊断] 已置parsing，开始调用Python处理 docId={}", docId);

                // 2. 调用 Python AI 服务处理文档
                long t0 = System.currentTimeMillis();
                Map<String, Object> result = aiServiceClient.processDocument(
                        docId, filePath, fileType, kbId, tenantId, aiConfig);
                log.info("[文档诊断] Python处理返回 docId={} 耗时={}ms result={}",
                        docId, System.currentTimeMillis() - t0, result);

                // 3. 根据结果更新状态
                String status = result.get("status") != null ? result.get("status").toString() : "failed";
                // M5 取消：Python 主流程内检测到取消（已清向量），Java 侧保持一致为 cancelled（终态）
                if ("cancelled".equals(status)) {
                    this.updateDocumentStatus(docId, "cancelled", null, null);
                    aiServiceClient.invalidateCache(tenantId);
                    log.info("文档处理被取消(主流程内): docId={}", docId);
                    return;
                }
                // 对齐 WeKnora finalizing=queryable：optimizing 表示向量已入库、文档已可检索，
                // 仅问答增强在后台进行中，同样视为成功落库（不阻塞用户检索）。
                if ("ready".equals(status) || "optimizing".equals(status)) {
                    // 竞态守卫：用户中途取消，Python 仍跑完返回 ready/optimizing
                    // （cancelDocument 已将状态置 cancelled 终态，终态守卫会拒绝本次回退），
                    // 此处主动清理已入库向量，避免残留可检索但已「取消」的文档。
                    Document cur = this.getById(docId);
                    if (cur != null && "cancelled".equals(cur.getStatus())) {
                        log.info("文档已被取消，清理已入库向量 docId={}", docId);
                        aiServiceClient.deleteDocument(docId);
                        aiServiceClient.invalidateCache(tenantId);
                        return;
                    }
                    Object chunkCountObj = result.get("chunk_count");
                    Integer chunkCount = chunkCountObj instanceof Number ? ((Number) chunkCountObj).intValue() : 0;
                    this.updateDocumentStatus(docId, status, chunkCount, null);
                    // 保存提取全文，供前端「查看内容」弹窗展示（optimizing 阶段亦可查看）
                    Object contentObj = result.get("content");
                    if (contentObj != null) {
                        this.saveDocumentContent(docId, contentObj.toString());
                    }
                    // 文档内容已变更，清该租户 L1 检索缓存，下次提问回源重新检索
                    aiServiceClient.invalidateCache(tenantId);
                    log.info("文档处理完成(可检索): docId={}, status={}, chunks={}", docId, status, chunkCount);
                } else {
                    String errorType = result.get("error_type") != null ? result.get("error_type").toString() : null;
                    String error = result.get("error") != null ? result.get("error").toString() : "未知错误";
                    // M3-3：模型配置错误（无 Key / Key 错 / 模型名错 / 维度不匹配）标记，前端引导重配
                    boolean modelConfigError = "MODEL_CONFIG_ERROR".equals(errorType);
                    this.updateDocumentStatus(docId, "failed", null, error, modelConfigError);
                    log.warn("文档处理失败: docId={}, errorType={}, error={}", docId, errorType, error);
                }
            } catch (Exception e) {
                log.error("文档处理异常: docId={}", docId, e);
                this.updateDocumentStatus(docId, "failed", null, e.getMessage());
            }
        });
    }
}
