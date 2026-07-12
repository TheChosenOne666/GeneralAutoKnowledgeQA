package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.mapper.AiConfigMapper;
import com.xiongda.model.dto.config.AiConfigUpdateRequest;
import com.xiongda.model.entity.AiConfig;
import com.xiongda.model.vo.AiConfigVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiConfigServiceImpl 单元测试 — 覆盖多模型（llmModels）的持久化与解析。
 *
 * <p>纯 Mockito 单元测试，mock AiConfigMapper，不依赖数据库。
 */
@ExtendWith(MockitoExtension.class)
class AiConfigServiceImplTest {

    @Mock
    private AiConfigMapper aiConfigMapper;

    @InjectMocks
    private AiConfigServiceImpl aiConfigService;

    @BeforeEach
    void setUp() {
        // ServiceImpl 的 baseMapper 由 Spring 启动时注入，单测需手动设置
        ReflectionTestUtils.setField(aiConfigService, "baseMapper", aiConfigMapper);
    }

    @Test
    void getConfig_parsesLlmModelsJsonToList() {
        AiConfig stored = new AiConfig();
        stored.setTenantId(1L);
        stored.setLlmModels("[\"deepseek-v3\",\"deepseek-r1\"]");
        stored.setLlmModel("deepseek-v3");
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(stored);

        AiConfigVO vo = aiConfigService.getConfig(1L, 2L);

        assertEquals(List.of("deepseek-v3", "deepseek-r1"), vo.getLlmModels());
        assertEquals("deepseek-v3", vo.getLlmModel());
    }

    @Test
    void getConfig_nullModels_returnsEmptyList() {
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);

        AiConfigVO vo = aiConfigService.getConfig(1L, 2L);

        assertNotNull(vo.getLlmModels());
        assertTrue(vo.getLlmModels().isEmpty());
    }

    @Test
    void updateConfig_persistsLlmModelsJsonAndFillsDefaultModel() {
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setLlmProvider("DeepSeek");
        req.setLlmModels(List.of("deepseek-v3", "deepseek-r1"));

        aiConfigService.updateConfig(1L, 2L, req);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insertOrUpdate(captor.capture());
        AiConfig saved = captor.getValue();
        assertEquals("[\"deepseek-v3\",\"deepseek-r1\"]", saved.getLlmModels());
        assertEquals("deepseek-v3", saved.getLlmModel()); // 默认模型为空时取多模型第一项
    }

    @Test
    void updateConfig_explicitDefaultModelTakesPrecedence() {
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setLlmModel("custom-default");
        req.setLlmModels(List.of("custom-default", "other"));

        aiConfigService.updateConfig(1L, 2L, req);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insertOrUpdate(captor.capture());
        assertEquals("custom-default", captor.getValue().getLlmModel());
    }
}
