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
        AiConfig config = getConfigEntity(tenantId, userId);
        if (config == null) {
            config = new AiConfig();
            config.setTenantId(tenantId);
            config.setUserId(userId);
        }

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
            // 默认模型（llmModel）为空时，取多模型列表第一项作为默认选中
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

        this.saveOrUpdate(config);
        return toVO(config);
    }

    /**
     * 获取用户级配置，不存在则查租户级默认。
     */
    private AiConfig getConfigEntity(Long tenantId, Long userId) {
        QueryWrapper<AiConfig> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId);
        queryWrapper.eq("user_id", userId);
        AiConfig config = this.getOne(queryWrapper);
        if (config != null) {
            return config;
        }
        // 查租户级默认
        queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId);
        queryWrapper.isNull("user_id");
        return this.getOne(queryWrapper);
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
