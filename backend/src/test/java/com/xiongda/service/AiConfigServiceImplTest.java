package com.xiongda.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiongda.mapper.AiConfigMapper;
import com.xiongda.model.dto.config.AiConfigUpdateRequest;
import com.xiongda.model.entity.AiConfig;
import com.xiongda.model.vo.AiConfigVO;
import com.xiongda.service.DocumentService;
import com.xiongda.service.impl.AiConfigServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 配置服务单元测试 — 覆盖平台级默认配置（tenant_id=0）与三级回退链。
 *
 * <p>使用 Mockito 纯单元测试，mock 掉 Mapper，不依赖数据库。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@ExtendWith(MockitoExtension.class)
class AiConfigServiceImplTest {

    @Mock
    private AiConfigMapper aiConfigMapper;

    @Mock
    private DocumentService documentService;

    private AiConfigServiceImpl aiConfigService;

    @BeforeEach
    void setUp() {
        aiConfigService = new AiConfigServiceImpl();
        ReflectionTestUtils.setField(aiConfigService, "baseMapper", aiConfigMapper);
        // @Resource 注入的 DocumentService 在单测中显式设置（updateConfig/updatePlatformDefault
        // 保存配置后会清除失败文档旧归因标记，缺省会导致 NPE）
        ReflectionTestUtils.setField(aiConfigService, "documentService", documentService);
    }

    // ==================== 平台级默认配置 ====================

    @Test
    void platformDefault_upsertAndRead() {
        // 首次无平台默认
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(null);
        AiConfigVO before = aiConfigService.getPlatformDefault();
        assertNull(before.getLlmModel());

        // 更新平台默认
        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setLlmProvider("deepseek");
        req.setLlmModel("deepseek-chat");
        req.setLlmModels(List.of("deepseek-chat", "deepseek-r1"));
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        AiConfigVO after = aiConfigService.updatePlatformDefault(req);
        assertEquals("deepseek-chat", after.getLlmModel());
        assertEquals(List.of("deepseek-chat", "deepseek-r1"), after.getLlmModels());
        verify(aiConfigMapper).insertOrUpdate(any(AiConfig.class));
    }

    // ==================== 三级回退链 ====================

    @Test
    void getConfig_fallsBackToPlatformDefault() {
        AiConfig platform = new AiConfig();
        platform.setId(1L);
        platform.setTenantId(0L);
        platform.setLlmModel("platform-model");

        // 回退链：用户级(null) -> 租户级(null) -> 平台级(platform)
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean()))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(platform);

        AiConfigVO vo = aiConfigService.getConfig(10L, 20L);
        assertEquals("platform-model", vo.getLlmModel());
    }

    @Test
    void updateConfig_doesNotTouchPlatformDefault() {
        // 更新用户级配置：精确查 (tenantId,userId)，不应误命中平台级
        AiConfig userCfg = new AiConfig();
        userCfg.setId(5L);
        when(aiConfigMapper.selectOne(any(QueryWrapper.class), anyBoolean())).thenReturn(userCfg);
        when(aiConfigMapper.insertOrUpdate(any(AiConfig.class))).thenReturn(true);

        AiConfigUpdateRequest req = new AiConfigUpdateRequest();
        req.setLlmModel("user-model");
        AiConfigVO vo = aiConfigService.updateConfig(10L, 20L, req);
        assertEquals("user-model", vo.getLlmModel());
        verify(aiConfigMapper).insertOrUpdate(any(AiConfig.class));
    }
}
