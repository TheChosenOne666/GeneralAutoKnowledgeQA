package com.xiongda.exception;

import com.xiongda.common.ErrorCode;
import lombok.Getter;

import java.io.Serializable;

/**
 * 自定义业务异常。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Getter
public class BusinessException extends RuntimeException implements Serializable {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}
