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
     * 异步触发文档处理 — 调用 Python AI 服务提取文本并分块。
     *
     * <p>流程：更新状态为 parsing → 调用 AI 服务 → 根据结果更新 ready/failed。
     *
     * @param docId    文档 ID
     * @param filePath 文件绝对路径
     * @param fileType 文件类型
     * @param kbId     知识库 ID
     * @param tenantId 租户 ID
     */
    void triggerDocumentProcessing(Long docId, String filePath, String fileType, Long kbId, Long tenantId);
}
