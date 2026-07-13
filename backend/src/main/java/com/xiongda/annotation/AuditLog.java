package com.xiongda.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录审计日志的业务方法。
 *
 * <p>由 {@code AuditLogAspect} 在方法成功返回后自动落库，自动抓取当前登录用户、客户端 IP 与 User-Agent。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /**
     * 操作类型：login / logout / doc_upload / doc_delete / config_update / member_change。
     */
    String action();

    /**
     * 资源类型：user / document / ai_config / member（可选）。
     */
    String resourceType() default "";
}
