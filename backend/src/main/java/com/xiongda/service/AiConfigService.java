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
     * 更新当前用户的 AI 配置。
     */
    AiConfigVO updateConfig(Long tenantId, Long userId, AiConfigUpdateRequest request);
}
