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

    private CommonConstant() {
    }
}
