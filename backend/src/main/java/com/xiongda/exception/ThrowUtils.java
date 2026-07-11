package com.xiongda.exception;

import com.xiongda.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 抛异常工具类 — 简化条件判断抛异常的写法。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Slf4j
public class ThrowUtils {

    /**
     * 如果条件为 true，抛出业务异常。
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BusinessException(errorCode);
        }
    }

    /**
     * 如果条件为 true，抛出业务异常（自定义消息）。
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BusinessException(errorCode, message);
        }
    }

    /**
     * 如果对象为 null，抛出未找到异常。
     */
    public static void throwIfNull(Object obj, ErrorCode errorCode) {
        if (obj == null) {
            throw new BusinessException(errorCode);
        }
    }
}
