package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.DocumentVO;

import java.util.List;

/**
 * 文档服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface DocumentService extends IService<Document> {

    /**
     * 上传文档（按知识库 scope + owner 鉴权：共享库仅租户管理员，个人库仅 owner）。
     */
    Long uploadDocument(Long kbId, Long tenantId, User user, String filename, String fileType,
                        Long fileSize, String filePath);

    /**
     * 查询知识库下的文档列表。
     */
    List<DocumentVO> listDocuments(Long kbId, Long tenantId);

    /**
     * 删除文档（按知识库 scope + owner 鉴权：共享库仅租户管理员，个人库仅 owner）。
     */
    boolean deleteDocument(Long docId, Long tenantId, User user);

    /**
     * 获取文档 VO。
     */
    DocumentVO getDocumentVO(Document doc);

    /**
     * 更新文档处理状态。
     *
     * @param docId      文档 ID
     * @param status     新状态（parsing / ready / failed）
     * @param chunkCount 分块数量（可空）
     * @param errorMsg   错误信息（可空）
     */
    boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg);

    /**
     * 更新文档处理状态（含模型配置错误标记）。
     *
     * @param docId           文档 ID
     * @param status          新状态（parsing / ready / failed）
     * @param chunkCount      分块数量（可空）
     * @param errorMsg        错误信息（可空）
     * @param modelConfigError 是否因模型配置错误导致失败（M3-3，引导用户重配）
     */
    boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg,
            Boolean modelConfigError);

    /**
     * 保存文档提取全文（Python 解析后回填，供前端「查看内容」弹窗展示）。
     */
    void saveDocumentContent(Long docId, String content);

    /**
     * 获取文档提取全文（仅同租户可读，按知识库 scope 鉴权）。
     */
    String getDocumentContent(Long docId, Long tenantId, User user);

    /**
     * 异步触发文档处理 — 调用 Python AI 服务提取文本并分块。
     *
     * <p>流程：更新状态为 parsing → 调用 AI 服务 → 根据结果更新 ready/failed。
     * M3-3：按上传用户解析其 AI 模型配置（含 API Key）透传给 Python。
     *
     * @param docId    文档 ID
     * @param filePath 文件绝对路径
     * @param fileType 文件类型
     * @param kbId     知识库 ID
     * @param tenantId 租户 ID
     * @param userId   上传用户 ID（用于解析用户级/租户级 AI 配置）
     */
    void triggerDocumentProcessing(Long docId, String filePath, String fileType, Long kbId, Long tenantId,
            Long userId);
}
