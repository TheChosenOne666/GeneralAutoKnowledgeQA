package com.xiongda.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解 — 标注在 Controller 方法上，校验用户角色。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {

    /**
     * 必须具备的角色（任一即可）
     */
    String[] mustRole() default {};
}
