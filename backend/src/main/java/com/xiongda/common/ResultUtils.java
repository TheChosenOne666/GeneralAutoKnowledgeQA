package com.xiongda.common;

/**
 * 响应工具类 — 快速构建 BaseResponse。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
public class ResultUtils {

    /**
     * 成功响应。
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data, "ok");
    }

    /**
     * 成功响应（无数据）。
     */
    public static BaseResponse<?> success() {
        return new BaseResponse<>(0, null, "ok");
    }

    /**
     * 失败响应。
     */
    public static BaseResponse<?> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /**
     * 失败响应（自定义消息）。
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }

    /**
     * 失败响应（自定义 code + 消息）。
     */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }
}
