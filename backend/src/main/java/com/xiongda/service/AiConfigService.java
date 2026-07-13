package com.xiongda.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiongda.model.dto.config.AiConfigUpdateRequest;
import com.xiongda.model.entity.AiConfig;
import com.xiongda.model.vo.AiConfigVO;

/**
 * AI 配置服务接口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public interface AiConfigService extends IService<AiConfig> {

    /**
     * 获取当前用户的 AI 配置（用户级 > 租户级 > 系统默认）。
     */
    AiConfigVO getConfig(Long tenantId, Long userId);

    /**
     * 获取当前用户原始 AI 配置（含 API Key，仅内部 Java → Python 透传使用，切勿返回前端）。
     *
     * <p>按用户级 > 租户级回退；两者皆无返回 null。
     */
    AiConfig getRawConfig(Long tenantId, Long userId);

    /**
     * 更新当前用户的 AI 配置。
     */
    AiConfigVO updateConfig(Long tenantId, Long userId, AiConfigUpdateRequest request);

    /**
     * 获取平台级默认 AI 配置（对所有租户生效的兜底）。
     */
    AiConfigVO getPlatformDefault();

    /**
     * 更新平台级默认 AI 配置（仅平台超管）。
     */
    AiConfigVO updatePlatformDefault(AiConfigUpdateRequest request);
}
