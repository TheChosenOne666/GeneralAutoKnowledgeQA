package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.mapper.KnowledgeBaseMapper;
import com.xiongda.model.entity.KnowledgeBase;
import com.xiongda.model.vo.KnowledgeBaseVO;
import com.xiongda.service.impl.KnowledgeBaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库服务实现单元测试 — 覆盖创建、列表查询、VO 转换。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseServiceImpl();
        ReflectionTestUtils.setField(knowledgeBaseService, "baseMapper", knowledgeBaseMapper);
    }

    // ==================== 创建知识库 ====================

    @Test
    void createKnowledgeBase_success() {
        doAnswer(inv -> {
            inv.getArgument(0, KnowledgeBase.class).setId(100L);
            return 1;
        }).when(knowledgeBaseMapper).insert(any(KnowledgeBase.class));

        Long id = knowledgeBaseService.createKnowledgeBase(1L, 10L, "测试库", "描述", "shared");
        assertEquals(100L, id);

        verify(knowledgeBaseMapper).insert(any(KnowledgeBase.class));
    }

    @Test
    void createKnowledgeBase_defaultScopePersonal() {
        doAnswer(inv -> {
            inv.getArgument(0, KnowledgeBase.class).setId(101L);
            return 1;
        }).when(knowledgeBaseMapper).insert(any(KnowledgeBase.class));

        knowledgeBaseService.createKnowledgeBase(1L, 10L, "个人库", null, null);

        verify(knowledgeBaseMapper).insert(any(KnowledgeBase.class));
    }

    @Test
    void createKnowledgeBase_nameBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.createKnowledgeBase(1L, 10L, "", null, "shared"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
    }

    // ==================== 列表查询 ====================

    @Test
    void listKnowledgeBases_all() {
        KnowledgeBase shared = buildKb(1L, "共享库", "shared", 10L);
        KnowledgeBase personal = buildKb(2L, "个人库", "personal", 10L);
        when(knowledgeBaseMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(shared, personal));

        List<KnowledgeBaseVO> result = knowledgeBaseService.listKnowledgeBases(1L, 10L, null);
        assertEquals(2, result.size());
    }

    @Test
    void listKnowledgeBases_sharedOnly() {
        KnowledgeBase shared = buildKb(1L, "共享库", "shared", 10L);
        when(knowledgeBaseMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(shared));

        List<KnowledgeBaseVO> result = knowledgeBaseService.listKnowledgeBases(1L, 10L, "shared");
        assertEquals(1, result.size());
        assertEquals("shared", result.get(0).getScope());
    }

    @Test
    void listKnowledgeBases_personalOnly() {
        KnowledgeBase personal = buildKb(2L, "个人库", "personal", 10L);
        when(knowledgeBaseMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(personal));

        List<KnowledgeBaseVO> result = knowledgeBaseService.listKnowledgeBases(1L, 10L, "personal");
        assertEquals(1, result.size());
        assertEquals("personal", result.get(0).getScope());
    }

    // ==================== VO 转换 ====================

    @Test
    void getKnowledgeBaseVO_null() {
        assertNull(knowledgeBaseService.getKnowledgeBaseVO(null));
    }

    @Test
    void getKnowledgeBaseVO_success() {
        KnowledgeBase kb = buildKb(1L, "测试库", "shared", 10L);
        kb.setDescription("描述");
        kb.setDocumentCount(5);

        KnowledgeBaseVO vo = knowledgeBaseService.getKnowledgeBaseVO(kb);
        assertEquals(1L, vo.getId());
        assertEquals("测试库", vo.getName());
        assertEquals("shared", vo.getScope());
        assertEquals(5, vo.getDocumentCount());
    }

    // ==================== 辅助方法 ====================

    private KnowledgeBase buildKb(Long id, String name, String scope, Long ownerId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setTenantId(1L);
        kb.setName(name);
        kb.setScope(scope);
        kb.setOwnerId(ownerId);
        kb.setDocumentCount(0);
        kb.setCreateTime(new Date());
        return kb;
    }
}
