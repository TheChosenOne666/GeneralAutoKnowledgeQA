package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.client.AiServiceClient;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.common.ErrorCode;
import com.xiongda.mapper.DocumentMapper;
import com.xiongda.model.entity.AiConfig;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.DocumentVO;
import com.xiongda.service.AiConfigService;
import com.xiongda.service.DocumentService;
import com.xiongda.service.KbPermission;
import com.xiongda.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    @Override
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
        doc.setStatus("pending");
        doc.setChunkCount(0);
        doc.setUploadedBy(user.getId());
        this.save(doc);
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
    public boolean deleteDocument(Long docId, Long tenantId, User user) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR);
        // 知识库写权限：租户隔离（第一维度）+ 共享库仅租户管理员 / 个人库仅 owner
        KnowledgeBase kb = knowledgeBaseService.getById(doc.getKbId());
        KbPermission.assertCanWrite(kb, user.getId(), tenantId, user.getRole());
        // TODO: 删除向量数据库中的数据
        // 文档删除同样改变检索结果，清该租户 L1 检索缓存
        aiServiceClient.invalidateCache(tenantId);
        return this.removeById(docId);
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
    public void triggerDocumentProcessing(Long docId, String filePath, String fileType, Long kbId, Long tenantId,
            Long userId) {
        // M3-3：按上传用户解析其 AI 模型配置（用户级 > 租户级），透传给 Python 真正消费。
        AiConfig rawConfig = aiConfigService.getRawConfig(tenantId, userId);
        Map<String, Object> aiConfig = AiServiceClient.toAiConfigMap(rawConfig);
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 更新状态为解析中
                this.updateDocumentStatus(docId, "parsing", null, null);

                // 2. 调用 Python AI 服务处理文档
                Map<String, Object> result = aiServiceClient.processDocument(
                        docId, filePath, fileType, kbId, tenantId, aiConfig);

                // 3. 根据结果更新状态
                String status = result.get("status") != null ? result.get("status").toString() : "failed";
                if ("ready".equals(status)) {
                    Object chunkCountObj = result.get("chunk_count");
                    Integer chunkCount = chunkCountObj instanceof Number ? ((Number) chunkCountObj).intValue() : 0;
                    this.updateDocumentStatus(docId, "ready", chunkCount, null);
                    // 文档内容已变更，清该租户 L1 检索缓存，下次提问回源重新检索
                    aiServiceClient.invalidateCache(tenantId);
                    log.info("文档处理完成: docId={}, chunks={}", docId, chunkCount);
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
