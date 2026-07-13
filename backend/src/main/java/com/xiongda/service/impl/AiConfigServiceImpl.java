package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongda.annotation.AuditLog;
import com.xiongda.mapper.AiConfigMapper;
import com.xiongda.model.dto.config.AiConfigUpdateRequest;
import com.xiongda.model.entity.AiConfig;
import com.xiongda.model.vo.AiConfigVO;
import com.xiongda.service.AiConfigService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 配置服务实现。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Service
public class AiConfigServiceImpl extends ServiceImpl<AiConfigMapper, AiConfig> implements AiConfigService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 平台级默认配置的租户哨兵（不等于任何真实租户 ID，对所有租户生效）。
     */
    private static final Long PLATFORM_TENANT_ID = 0L;

    @Override
    public AiConfigVO getConfig(Long tenantId, Long userId) {
        // 优先查用户级配置
        AiConfig config = getConfigEntity(tenantId, userId);
        if (config == null) {
            config = new AiConfig();
            config.setTenantId(tenantId);
        }
        return toVO(config);
    }

    @Override
    public AiConfig getRawConfig(Long tenantId, Long userId) {
        return getConfigEntity(tenantId, userId);
    }

    @Override
    @AuditLog(action = "config_update", resourceType = "ai_config")
    public AiConfigVO updateConfig(Long tenantId, Long userId, AiConfigUpdateRequest req) {
        AiConfig config = selectConfig(tenantId, userId);
        if (config == null) {
            config = new AiConfig();
            config.setTenantId(tenantId);
            config.setUserId(userId);
        }
        applyUpdate(config, req);
        this.saveOrUpdate(config);
        return toVO(config);
    }

    /**
     * 获取配置回退链：用户级 → 租户级默认 → 平台级默认。
     */
    private AiConfig getConfigEntity(Long tenantId, Long userId) {
        AiConfig config = selectConfig(tenantId, userId);
        if (config != null) {
            return config;
        }
        config = selectConfig(tenantId, null);
        if (config != null) {
            return config;
        }
        return selectConfig(PLATFORM_TENANT_ID, null);
    }

    /**
     * 精确查询某 (tenantId, userId) 配置；userId 为 null 表示租户 / 平台级默认。
     */
    private AiConfig selectConfig(Long tenantId, Long userId) {
        QueryWrapper<AiConfig> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId);
        if (userId == null) {
            queryWrapper.isNull("user_id");
        } else {
            queryWrapper.eq("user_id", userId);
        }
        return this.getOne(queryWrapper);
    }

    /**
     * 获取平台级默认配置（tenant_id=0，对所有租户生效的兜底）。
     */
    @Override
    public AiConfigVO getPlatformDefault() {
        AiConfig config = selectConfig(PLATFORM_TENANT_ID, null);
        if (config == null) {
            config = new AiConfig();
            config.setTenantId(PLATFORM_TENANT_ID);
        }
        return toVO(config);
    }

    /**
     * 更新平台级默认配置（仅平台超管）。
     */
    @Override
    @AuditLog(action = "config_update", resourceType = "ai_config")
    public AiConfigVO updatePlatformDefault(AiConfigUpdateRequest req) {
        AiConfig config = selectConfig(PLATFORM_TENANT_ID, null);
        if (config == null) {
            config = new AiConfig();
            config.setTenantId(PLATFORM_TENANT_ID);
        }
        applyUpdate(config, req);
        this.saveOrUpdate(config);
        return toVO(config);
    }

    /**
     * 将请求字段应用到配置实体（用户级 / 平台级复用）。
     */
    private void applyUpdate(AiConfig config, AiConfigUpdateRequest req) {
        if (StringUtils.isNotBlank(req.getLlmProvider())) config.setLlmProvider(req.getLlmProvider());
        if (StringUtils.isNotBlank(req.getLlmModel())) config.setLlmModel(req.getLlmModel());
        if (StringUtils.isNotBlank(req.getLlmApiKey())) config.setLlmApiKey(req.getLlmApiKey());
        if (StringUtils.isNotBlank(req.getLlmBaseUrl())) config.setLlmBaseUrl(req.getLlmBaseUrl());
        if (req.getLlmTemperature() != null) config.setLlmTemperature(req.getLlmTemperature());
        if (req.getLlmMaxTokens() != null) config.setLlmMaxTokens(req.getLlmMaxTokens());
        if (req.getLlmModels() != null) {
            try {
                config.setLlmModels(OBJECT_MAPPER.writeValueAsString(req.getLlmModels()));
            } catch (Exception e) {
                config.setLlmModels(null);
            }
            if (StringUtils.isBlank(config.getLlmModel()) && !req.getLlmModels().isEmpty()) {
                config.setLlmModel(req.getLlmModels().get(0));
            }
        }
        if (StringUtils.isNotBlank(req.getEmbeddingProvider())) config.setEmbeddingProvider(req.getEmbeddingProvider());
        if (StringUtils.isNotBlank(req.getEmbeddingModel())) config.setEmbeddingModel(req.getEmbeddingModel());
        if (StringUtils.isNotBlank(req.getEmbeddingApiKey())) config.setEmbeddingApiKey(req.getEmbeddingApiKey());
        if (StringUtils.isNotBlank(req.getEmbeddingBaseUrl())) config.setEmbeddingBaseUrl(req.getEmbeddingBaseUrl());
        if (req.getEmbeddingDimension() != null) config.setEmbeddingDimension(req.getEmbeddingDimension());
        if (StringUtils.isNotBlank(req.getRerankProvider())) config.setRerankProvider(req.getRerankProvider());
        if (StringUtils.isNotBlank(req.getRerankModel())) config.setRerankModel(req.getRerankModel());
        if (StringUtils.isNotBlank(req.getRerankApiKey())) config.setRerankApiKey(req.getRerankApiKey());
    }

    private AiConfigVO toVO(AiConfig config) {
        AiConfigVO vo = new AiConfigVO();
        vo.setLlmProvider(config.getLlmProvider());
        vo.setLlmModel(config.getLlmModel());
        vo.setLlmBaseUrl(config.getLlmBaseUrl());
        vo.setLlmTemperature(config.getLlmTemperature());
        vo.setLlmMaxTokens(config.getLlmMaxTokens());
        // 多模型列表：JSON 反序列化为 List；解析失败或无值返回空列表，避免前端 NPE
        if (config.getLlmModels() != null && !config.getLlmModels().isBlank()) {
            try {
                vo.setLlmModels(OBJECT_MAPPER.readValue(
                        config.getLlmModels(), new TypeReference<List<String>>() {}));
            } catch (Exception e) {
                vo.setLlmModels(List.of());
            }
        } else {
            vo.setLlmModels(List.of());
        }
        vo.setEmbeddingProvider(config.getEmbeddingProvider());
        vo.setEmbeddingModel(config.getEmbeddingModel());
        vo.setEmbeddingBaseUrl(config.getEmbeddingBaseUrl());
        vo.setEmbeddingDimension(config.getEmbeddingDimension());
        vo.setRerankProvider(config.getRerankProvider());
        vo.setRerankModel(config.getRerankModel());
        vo.setHasRerank(StringUtils.isNotBlank(config.getRerankProvider()));
        return vo;
    }
}
