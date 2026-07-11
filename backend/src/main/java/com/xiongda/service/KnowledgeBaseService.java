package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.vo.KnowledgeBaseVO;

import java.util.List;

/**
 * 知识库服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    /**
     * 创建知识库。
     */
    Long createKnowledgeBase(Long tenantId, Long userId, String name, String description, String scope);

    /**
     * 查询知识库列表（共享库 + 个人库）。
     */
    List<KnowledgeBaseVO> listKnowledgeBases(Long tenantId, Long userId, String scope);

    /**
     * 获取知识库 VO。
     */
    KnowledgeBaseVO getKnowledgeBaseVO(KnowledgeBase kb);
}
