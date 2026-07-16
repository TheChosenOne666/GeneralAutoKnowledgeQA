package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.mapper.AiConfigMapper;
import com.xiongda.model.dto.config.AiConfigUpdateRequest;
import com.xiongda.model.entity.AiConfig;
import com.xiongda.model.vo.AiConfigVO;
import com.xiongda.service.DocumentService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Mock
    private DocumentService documentService;

    @InjectMocks
    private AiConfigServiceImpl aiConfigService;

    @BeforeEach
    void setUp() {
        // ServiceImpl 的 baseMapper 由 Spring 启动时注入，单测需手动设置
        ReflectionTestUtils.setField(aiConfigService, "baseMapper", aiConfigMapper);
        // @Resource 注入的 DocumentService 在单测中显式设置
        ReflectionTestUtils.setField(aiConfigService, "documentService", documentService);
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
        req.setLlmModel("deepseek-r1"); // 主模型显式填写（必填），与列表第一项不同以证未自动取列表首项
        req.setLlmModels(List.of("deepseek-v3", "deepseek-r1"));

        aiConfigService.updateConfig(1L, 2L, req);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insertOrUpdate(captor.capture());
        AiConfig saved = captor.getValue();
        assertEquals("[\"deepseek-v3\",\"deepseek-r1\"]", saved.getLlmModels());
        // 主模型以显式填写为准，不会自动取列表第一项填充。
        assertEquals("deepseek-r1", saved.getLlmModel());
    }

    @Test
    void updateConfig_blankFieldClearsStoredValue() {
        AiConfig stored = new AiConfig();
        stored.setLlmModel("old-model");
        stored.setEmbeddingModel("old-emb");
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(stored);
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        // provider 留空，仅验证「删掉模型名即清空旧值」（不触发模型必填拦截）
        req.setLlmModel(""); // 主模型留空：应清空旧值，而非保留
        req.setEmbeddingModel(""); // Embedding 模型留空：应清空旧值

        aiConfigService.updateConfig(1L, 2L, req);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insertOrUpdate(captor.capture());
        AiConfig saved = captor.getValue();
        assertNull(saved.getLlmModel());
        assertNull(saved.getEmbeddingModel());
    }

    @Test
    void updateConfig_missingLlmModelWhenProviderSet_throws() {
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setLlmProvider("OpenAI");
        req.setLlmModel(""); // provider 已填但模型未填

        assertThrows(IllegalArgumentException.class,
                () -> aiConfigService.updateConfig(1L, 2L, req));
    }

    @Test
    void updateConfig_missingEmbeddingModelWhenProviderSet_throws() {
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setEmbeddingProvider("阿里云百炼");
        req.setEmbeddingModel(""); // provider 已填但模型未填

        assertThrows(IllegalArgumentException.class,
                () -> aiConfigService.updateConfig(1L, 2L, req));
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

    @Test
    void updateConfig_embeddingDimensionRequired() {
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setEmbeddingProvider("阿里云百炼");
        req.setEmbeddingModel("text-embedding-v3");
        req.setEmbeddingApiKey("sk-test");
        // 未填向量维度

        assertThrows(IllegalArgumentException.class,
                () -> aiConfigService.updateConfig(1L, 2L, req));
    }

    @Test
    void updateConfig_embeddingWithDimensionSucceeds() {
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setEmbeddingProvider("阿里云百炼");
        req.setEmbeddingModel("text-embedding-v3");
        req.setEmbeddingApiKey("sk-test");
        req.setEmbeddingDimension(1024);

        aiConfigService.updateConfig(1L, 2L, req);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insertOrUpdate(captor.capture());
        assertEquals(1024, captor.getValue().getEmbeddingDimension());
    }

    @Test
    void updateConfig_clearsFailedDocErrorFlagsForTenant() {
        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setEmbeddingProvider("OpenAI");
        req.setEmbeddingModel("text-embedding-3-small");
        req.setEmbeddingDimension(1536);
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        aiConfigService.updateConfig(5L, 9L, req);

        // 保存配置成功后应清除该租户失败文档基于旧配置的归因标记
        verify(documentService).clearFailedConfigErrorFlags(5L);
    }

    @Test
    void updatePlatformDefault_clearsFailedDocErrorFlagsGlobally() {
        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setEmbeddingProvider("OpenAI");
        req.setEmbeddingModel("text-embedding-3-small");
        req.setEmbeddingDimension(1536);
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        aiConfigService.updatePlatformDefault(req);

        // 平台级默认配置影响所有租户，应清除全库失败文档的归因标记（tenantId=null）
        verify(documentService).clearFailedConfigErrorFlags(null);
    }
}
