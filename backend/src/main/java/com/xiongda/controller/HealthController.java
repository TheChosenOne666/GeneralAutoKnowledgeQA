package com.xiongda.controller;

import com.xiongda.common.BaseResponse;
import com.xiongda.common.ResultUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public BaseResponse<Map<String, String>> health() {
        return ResultUtils.success(Map.of("status", "ok", "app", "熊答"));
    }
}
