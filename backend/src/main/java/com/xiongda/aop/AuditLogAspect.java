package com.xiongda.aop;

import com.xiongda.annotation.AuditLog;
import com.xiongda.common.BaseResponse;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.LoginUserVO;
import com.xiongda.service.AuditLogService;
import com.xiongda.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志切面 — 拦截 {@link AuditLog} 标注的方法，方法成功返回后自动记录操作审计。
 *
 * <p>自动抓取：当前登录用户（未登录场景如登录/接受邀请则从返回值取）、客户端 IP、User-Agent；
 * 并尽量从返回值提取资源 ID、从入参构造脱敏后的 detail JSON。审计写入失败仅告警，不影响主流程。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private final UserService userService;
    private final AuditLogService auditLogService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditLogAspect(UserService userService, AuditLogService auditLogService) {
        this.userService = userService;
        this.auditLogService = auditLogService;
    }

    /**
     * 方法成功返回后记录审计。
     */
    @AfterReturning(pointcut = "@annotation(auditLog)", returning = "result")
    public void record(JoinPoint joinPoint, AuditLog auditLog, Object result) {
        try {
            HttpServletRequest request = currentRequest();
            String ip = clientIp(request);
            String ua = request != null ? request.getHeader("User-Agent") : null;
            Operator op = resolveOperator(request, result);
            String resourceId = resolveResourceId(result);
            String detail = buildDetail(joinPoint.getArgs());

            auditLogService.recordLog(
                    op != null ? op.tenantId() : null,
                    op != null ? op.userId() : null,
                    op != null ? op.email() : null,
                    auditLog.action(),
                    auditLog.resourceType(),
                    resourceId,
                    detail,
                    ip,
                    ua);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 解析操作者：已登录走登录态；未登录（登录/接受邀请）从返回值 LoginUserVO 取。
     */
    private Operator resolveOperator(HttpServletRequest request, Object result) {
        if (request != null) {
            try {
                User u = userService.getLoginUser(request);
                return new Operator(u.getTenantId(), u.getId(), u.getEmail());
            } catch (Exception ignored) {
                // 未登录场景，继续从返回值取
            }
        }
        Object data = unwrap(result);
        if (data instanceof LoginUserVO vo) {
            return new Operator(vo.getTenantId(), vo.getId(), vo.getEmail());
        }
        return null;
    }

    /**
     * 从返回值提取资源 ID（Long 或 LoginUserVO.id）。
     */
    private String resolveResourceId(Object result) {
        Object data = unwrap(result);
        if (data instanceof Long l) {
            return l.toString();
        }
        if (data instanceof LoginUserVO vo && vo.getId() != null) {
            return vo.getId().toString();
        }
        return null;
    }

    /**
     * 由入参构造脱敏 detail（屏蔽密码 / 密钥 / token 等敏感字段，跳过请求与文件对象）。
     */
    private String buildDetail(Object[] args) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (args != null) {
                for (Object arg : args) {
                    if (arg == null) {
                        continue;
                    }
                    if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                            || arg instanceof MultipartFile || arg instanceof Part
                            || arg instanceof User) {
                        continue;
                    }
                    try {
                        String json = mapper.writeValueAsString(arg);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = mapper.readValue(json, Map.class);
                        maskSensitive(m);
                        detail.putAll(m);
                    } catch (Exception ignored) {
                        // 跳过不可序列化参数
                    }
                }
            }
            if (detail.isEmpty()) {
                return null;
            }
            return mapper.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 递归将敏感字段替换为 `***`。
     */
    @SuppressWarnings("unchecked")
    private void maskSensitive(Map<String, Object> map) {
        for (Map.Entry<String, Object> e : new ArrayList<>(map.entrySet())) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val instanceof Map) {
                maskSensitive((Map<String, Object>) val);
            } else if (val instanceof List) {
                for (Object item : (List<?>) val) {
                    if (item instanceof Map) {
                        maskSensitive((Map<String, Object>) item);
                    }
                }
            } else if (isSensitiveKey(key)) {
                map.put(key, "***");
            }
        }
    }

    private boolean isSensitiveKey(String key) {
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("secret")
                || k.contains("token") || k.contains("apikey") || k.contains("api_key");
    }

    private Object unwrap(Object result) {
        if (result instanceof BaseResponse<?> br) {
            return br.getData();
        }
        return result;
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (org.apache.commons.lang3.StringUtils.isNotBlank(xff) && !"unknown".equalsIgnoreCase(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 操作者信息（tenantId 可能为 null，如平台超管）。
     */
    private record Operator(Long tenantId, Long userId, String email) {
    }
}
