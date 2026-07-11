package com.xiongda.controller;

import com.xiongda.common.BaseResponse;
import com.xiongda.common.ResultUtils;
import com.xiongda.model.dto.config.AiConfigUpdateRequest;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.AiConfigVO;
import com.xiongda.service.AiConfigService;
import com.xiongda.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * AI 模型配置控制器。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
@RequestMapping("/api/ai-config")
public class AiConfigController {

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private UserService userService;

    /**
     * 获取当前用户的 AI 配置。
     */
    @GetMapping("/")
    public BaseResponse<AiConfigVO> getConfig(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        AiConfigVO config = aiConfigService.getConfig(loginUser.getTenantId(), loginUser.getId());
        return ResultUtils.success(config);
    }

    /**
     * 更新当前用户的 AI 配置。
     */
    @PostMapping("/update")
    public BaseResponse<AiConfigVO> updateConfig(@RequestBody AiConfigUpdateRequest body, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        AiConfigVO config = aiConfigService.updateConfig(loginUser.getTenantId(), loginUser.getId(), body);
        return ResultUtils.success(config);
    }
}
