package com.xiongda.aop;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求日志 AOP 切面 — 记录每个请求的耗时和参数。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
@Aspect
@Component
public class LogInterceptor {

    @Around("execution(* com.xiongda.controller.*.*(..))")
    public Object doInterceptor(ProceedingJoinPoint joinPoint) throws Throwable {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // 记录请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String uri = request.getRequestURI();
            String method = request.getMethod();
            log.info("Request Start | {} {}", method, uri);
        }

        // 执行
        Object result = joinPoint.proceed();

        // 记录耗时
        stopWatch.stop();
        long totalTimeMillis = stopWatch.getTotalTimeMillis();
        log.info("Request End | cost {}ms", totalTimeMillis);

        return result;
    }
}
