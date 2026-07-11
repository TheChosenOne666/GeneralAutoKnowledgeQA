package com.xiongda.constant;

/**
 * 用户常量。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public class UserConstant {

    /**
     * 用户登录态 key（Redis）
     */
    public static final String USER_LOGIN_STATE = "user:login:";

    /**
     * 角色枚举
     */
    public static final String DEFAULT_ROLE = "member";

    public static final String TENANT_ADMIN_ROLE = "tenant_admin";

    public static final String SUPER_ADMIN_ROLE = "super_admin";

    private UserConstant() {
    }
}
