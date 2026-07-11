package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.common.ErrorCode;
import com.xiongda.mapper.DocumentMapper;
import com.xiongda.model.entity.Document;
import com.xiongda.model.vo.DocumentVO;
import com.xiongda.service.DocumentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements DocumentService {

    @Override
    public Long uploadDocument(Long kbId, Long tenantId, Long userId, String filename, String fileType,
                               Long fileSize, String filePath) {
        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setTenantId(tenantId);
        doc.setFilename(filename);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setFilePath(filePath);
        doc.setStatus("pending");
        doc.setChunkCount(0);
        doc.setUploadedBy(userId);
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
    public boolean deleteDocument(Long docId, Long tenantId) {
        Document doc = this.getById(docId);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        ThrowUtils.throwIf(!tenantId.equals(doc.getTenantId()), ErrorCode.NO_AUTH_ERROR);
        // TODO: 删除向量数据库中的数据
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
        vo.setCreateTime(doc.getCreateTime());
        return vo;
    }
}
