package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.Document;
import com.xiongda.model.vo.DocumentVO;

import java.util.List;

/**
 * 文档服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface DocumentService extends IService<Document> {

    /**
     * 上传文档。
     */
    Long uploadDocument(Long kbId, Long tenantId, Long userId, String filename, String fileType,
                        Long fileSize, String filePath);

    /**
     * 查询知识库下的文档列表。
     */
    List<DocumentVO> listDocuments(Long kbId, Long tenantId);

    /**
     * 删除文档。
     */
    boolean deleteDocument(Long docId, Long tenantId);

    /**
     * 获取文档 VO。
     */
    DocumentVO getDocumentVO(Document doc);
}
