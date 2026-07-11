package com.xiongda.aop;

import com.xiongda.annotation.AuthCheck;
import com.xiongda.common.ErrorCode;
import com.xiongda.exception.BusinessException;
import com.xiongda.model.entity.User;
import com.xiongda.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 权限校验 AOP 切面 — 拦截 @AuthCheck 注解的方法。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Aspect
@Component
public class AuthInterceptor {

    @Resource
    private UserService userService;

    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        String[] mustRoles = authCheck.mustRole();
        if (ArrayUtils.isEmpty(mustRoles)) {
            return joinPoint.proceed();
        }

        // 获取当前登录用户
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;

        User loginUser = userService.getLoginUser(request);
        String userRole = loginUser.getRole();

        // 校验角色
        boolean hasRole = false;
        for (String role : mustRoles) {
            if (role.equals(userRole)) {
                hasRole = true;
                break;
            }
        }
        if (!hasRole) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        return joinPoint.proceed();
    }
}
