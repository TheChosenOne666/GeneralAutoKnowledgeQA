package com.xiongda.model.enums;

import lombok.Getter;

/**
 * 文档处理状态枚举。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Getter
public enum DocStatusEnum {

    PENDING("pending", "等待处理"),
    PARSING("parsing", "正在解析"),
    EMBEDDING("embedding", "正在向量化"),
    READY("ready", "就绪可用"),
    FAILED("failed", "处理失败");

    private final String value;

    private final String text;

    DocStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static DocStatusEnum getEnumByValue(String value) {
        for (DocStatusEnum status : DocStatusEnum.values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
