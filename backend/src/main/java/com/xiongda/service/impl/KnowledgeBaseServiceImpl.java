package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.common.ErrorCode;
import com.xiongda.mapper.KnowledgeBaseMapper;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.KnowledgeBaseVO;
import com.xiongda.service.KbPermission;
import com.xiongda.service.KnowledgeBaseService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    @Override
    public Long createKnowledgeBase(Long tenantId, User user, String name, String description, String scope) {
        ThrowUtils.throwIf(StringUtils.isBlank(name), ErrorCode.PARAMS_ERROR, "知识库名称不能为空");
        // 共享库仅租户管理员 / 平台超管可创建
        KbPermission.assertCanCreate(scope, user.getRole());
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenantId);
        kb.setName(name);
        kb.setDescription(description);
        kb.setScope(StringUtils.isNotBlank(scope) ? scope : "personal");
        kb.setOwnerId(user.getId());
        kb.setDocumentCount(0);
        this.save(kb);
        return kb.getId();
    }

    @Override
    public List<KnowledgeBaseVO> listKnowledgeBases(Long tenantId, Long userId, String scope) {
        QueryWrapper<KnowledgeBase> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId);

        if (StringUtils.isNotBlank(scope)) {
            if ("personal".equals(scope)) {
                queryWrapper.eq("scope", "personal").eq("owner_id", userId);
            } else {
                queryWrapper.eq("scope", "shared");
            }
        } else {
            // 共享库 + 自己的个人库
            queryWrapper.and(w -> w
                    .eq("scope", "shared")
                    .or(sub -> sub.eq("scope", "personal").eq("owner_id", userId))
            );
        }
        queryWrapper.orderByDesc("create_time");

        List<KnowledgeBase> kbs = this.list(queryWrapper);
        return kbs.stream().map(this::getKnowledgeBaseVO).toList();
    }

    @Override
    public KnowledgeBaseVO getKnowledgeBaseVO(KnowledgeBase kb) {
        if (kb == null) {
            return null;
        }
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(kb.getId());
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setScope(kb.getScope());
        vo.setOwnerId(kb.getOwnerId());
        vo.setDocumentCount(kb.getDocumentCount());
        vo.setCreateTime(kb.getCreateTime());
        return vo;
    }
}
