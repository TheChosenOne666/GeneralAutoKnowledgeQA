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
        // Embedding 向量维度必填：只要配置了 Embedding（provider / model 任一非空），
        // 就必须填写正整数向量维度，否则拒绝保存，引导用户到 AI 配置页补全。
        if (StringUtils.isNotBlank(req.getEmbeddingProvider())
                || StringUtils.isNotBlank(req.getEmbeddingModel())) {
            Integer dim = req.getEmbeddingDimension();
            if (dim == null || dim <= 0) {
                throw new IllegalArgumentException("Embedding 向量维度为必填项且必须为正整数，请在 AI 配置页填写向量维度");
            }
        }
        // 模型必填校验：只要配置了对应模块（provider 非空），就必须填写模型名称，
        // 否则拒绝保存并提示用户补全，避免「删掉模型名却保存成功」导致后续 LLM / 向量化调用失败。
        if (StringUtils.isNotBlank(req.getLlmProvider()) && StringUtils.isBlank(req.getLlmModel())) {
            throw new IllegalArgumentException("LLM 模型名称为必填项，请在 AI 配置页填写 LLM 模型名称");
        }
        if (StringUtils.isNotBlank(req.getEmbeddingProvider()) && StringUtils.isBlank(req.getEmbeddingModel())) {
            throw new IllegalArgumentException("Embedding 模型名称为必填项，请在 AI 配置页填写 Embedding 模型名称");
        }
        // 非密钥字段：空白即清空（空白统一归一为 null，不再用 isNotBlank 守卫保留旧值），
        // 与前端「删除字段后保存即清空」一致，避免保存后字段被旧值自动回填。
        // 仅 API Key 保留「留空不修改」语义，避免误覆盖已存密钥（前端占位提示亦如此）。
        config.setLlmProvider(toBlankable(req.getLlmProvider()));
        config.setLlmModel(toBlankable(req.getLlmModel()));
        if (StringUtils.isNotBlank(req.getLlmApiKey())) config.setLlmApiKey(req.getLlmApiKey());
        config.setLlmBaseUrl(toBlankable(req.getLlmBaseUrl()));
        config.setLlmTemperature(req.getLlmTemperature());
        config.setLlmMaxTokens(req.getLlmMaxTokens());
        if (req.getLlmModels() != null) {
            try {
                config.setLlmModels(OBJECT_MAPPER.writeValueAsString(req.getLlmModels()));
            } catch (Exception e) {
                config.setLlmModels(null);
            }
        }
        config.setEmbeddingProvider(toBlankable(req.getEmbeddingProvider()));
        config.setEmbeddingModel(toBlankable(req.getEmbeddingModel()));
        if (StringUtils.isNotBlank(req.getEmbeddingApiKey())) config.setEmbeddingApiKey(req.getEmbeddingApiKey());
        config.setEmbeddingBaseUrl(toBlankable(req.getEmbeddingBaseUrl()));
        config.setEmbeddingDimension(req.getEmbeddingDimension());
        config.setRerankProvider(toBlankable(req.getRerankProvider()));
        config.setRerankModel(toBlankable(req.getRerankModel()));
        if (StringUtils.isNotBlank(req.getRerankApiKey())) config.setRerankApiKey(req.getRerankApiKey());
    }

    /** 将字符串空白（null 或全空白）归一为 null，用于非密钥字段「空白即清空」语义。*/
    private static String toBlankable(String v) {
        return StringUtils.isNotBlank(v) ? v : null;
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
