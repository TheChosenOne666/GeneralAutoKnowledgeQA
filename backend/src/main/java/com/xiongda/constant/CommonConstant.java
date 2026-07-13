package com.xiongda.constant;

/**
 * 通用常量。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public class CommonConstant {

    /**
     * 分页默认页号
     */
    public static final long DEFAULT_CURRENT = 1L;

    /**
     * 分页默认页面大小
     */
    public static final long DEFAULT_PAGE_SIZE = 10L;

    /**
     * 最大页面大小
     */
    public static final long MAX_PAGE_SIZE = 100L;

    /**
     * JWT Token 请求头
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * JWT Token 前缀
     */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * 平台超管切换目标租户时携带的租户ID请求头（对齐 WeKnora 的 TenantSelector）。
     * 仅当登录用户为 super_admin 时后端才采纳该头，普通用户忽略以防越权切换租户。
     */
    public static final String TENANT_HEADER = "X-Tenant-ID";

    private CommonConstant() {
    }
}
