package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.DocumentVO;
import com.xiongda.model.vo.PageContentVO;

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
     * 批量删除文档（按知识库 scope + owner 鉴权，同单删逻辑）。
     *
     * <p>fail-fast：先校验全部文档（存在 / 租户隔离 / 写权限），任一不通过即抛异常、不删除任何文档，
     * 避免部分删除的中间态。全部校验通过后逐个删向量 + 逻辑删除，最后只清一次该租户 L1 检索缓存、
     * 同步涉及知识库的文档数（较单删循环 N 次清缓存更高效）。</p>
     *
     * @param docIds   要删除的文档 ID 列表（去重、非空）
     * @param tenantId 租户 ID（用于隔离校验）
     * @param user     当前登录用户（用于知识库写权限校验）
     * @return 实际删除成功的文档数量
     */
    int deleteDocuments(List<Long> docIds, Long tenantId, User user);

    /**
     * 取消文档处理（软取消，保留文档记录）。
     *
     * <p>仅非终态（processing/parsing/retrieving/optimizing）可取消；取消后将文档标记为
     * {@code cancelled}（终态），并通知 Python 清理已写入的向量、取消排队的问答增强任务。
     * ready/failed/cancelled 已是终态，调用幂等返回 true（无需重复取消）。</p>
     *
     * @param docId    文档 ID
     * @param tenantId 租户 ID（用于隔离校验）
     * @param user     当前登录用户（用于知识库写权限校验）
     */
    boolean cancelDocument(Long docId, Long tenantId, User user);

    /**
     * 重试处理失败的文档（重新触发解析 / 分块 / 向量化）。
     *
     * <p>仅终态 {@code failed} / {@code cancelled} 可重试（网络抖动、模型配置已修正后等场景）；
     * 处理中（processing/parsing/...）或已就绪（ready）无需重试，调用抛业务异常。
     * 重试复用原上传者的 AI 模型配置（保证文档与上传者配置绑定一致），并校验原文件仍存在，
     * 否则提示重新上传。状态由终态重置为 processing 并清空错误标记 / 分块数。</p>
     *
     * @param docId    文档 ID
     * @param tenantId 租户 ID（用于隔离校验）
     * @param user     当前登录用户（用于知识库写权限校验）
     */
    boolean retryDocument(Long docId, Long tenantId, User user);

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
     * 更新文档处理状态（含模型配置错误 / 额度限流双标记）。
     *
     * <p>在 5 参版本基础上增加 {@code quotaError}：区分「模型配置错误」与「模型额度不足 / 被限流」，
     * 前端据此分别引导「去重配」或「稍后重试 / 检查账户额度」，避免把额度问题误判为配置错误。
     *
     * @param docId           文档 ID
     * @param status          新状态（parsing / ready / failed）
     * @param chunkCount      分块数量（可空）
     * @param errorMsg        错误信息（可空）
     * @param modelConfigError 是否因模型配置错误导致失败（引导重配）
     * @param quotaError       是否因模型额度不足 / 被限流导致失败（引导重试 / 检查额度）
     */
    boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg,
            Boolean modelConfigError, Boolean quotaError);

    /**
     * 更新文档处理状态（M5-1 增强版：额外携带文档全文 content）。
     *
     * <p>content 非空时（optimizing 阶段回调）一并保存提取全文，供前端「查看内容」弹窗展示，
     * 替代旧版依赖 ``/ai/document/process`` 同步返回落库全文的方式。</p>
     *
     * @param docId           文档 ID
     * @param status          新状态
     * @param chunkCount      分块数量（可空）
     * @param errorMsg        错误信息（可空）
     * @param modelConfigError 是否因模型配置错误导致失败（引导重配）
     * @param quotaError       是否因模型额度不足 / 被限流导致失败（引导重试 / 检查额度）
     * @param content         文档提取全文（可空，M5-1 经回调回填）
     */
    boolean updateDocumentStatus(Long docId, String status, Integer chunkCount, String errorMsg,
            Boolean modelConfigError, Boolean quotaError, String content);

    /**
     * 保存文档提取全文（Python 解析后回填，供前端「查看内容」弹窗展示）。
     */
    void saveDocumentContent(Long docId, String content);

    /**
     * 获取文档提取全文（仅同租户可读，按知识库 scope 鉴权）。
     */
    String getDocumentContent(Long docId, Long tenantId, User user);

    /**
     * 获取文档按页分段的文本（供前端预览真实翻页，M4-4 增强）。
     *
     * <p>优先调 AI 服务真实解析（PDF 真实页码 / docx/txt/md 估算页码，与引用来源一致）；
     * AI 不可用或失败时降级到已存全文按 CHARS_PER_PAGE 估算分页。</p>
     */
    List<PageContentVO> getDocumentPages(Long docId, Long tenantId, User user);

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

    /**
     * 清除失败文档上基于旧 AI 配置的归因标记（模型配置错误 / 额度限流）。
     *
     * <p>当用户重新保存 AI 模型配置后，历史失败文档上由旧配置归因得到的
     * {@code model_config_error} / {@code quota_error} 已不可信，应清除，
     * 避免知识库页持续展示「模型配置不正确，请重新配置」横幅。文档仍保持
     * {@code failed} 终态，需用户手动重试以按新配置重新归因。
     *
     * @param tenantId 租户 ID；为 null 时清除全库失败文档（如平台级默认配置更新影响所有租户）
     * @return 被更新的文档数量
     */
    int clearFailedConfigErrorFlags(Long tenantId);
}
