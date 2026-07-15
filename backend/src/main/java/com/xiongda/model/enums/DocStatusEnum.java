package com.xiongda.model.enums;

import lombok.Getter;

/**
 * 文档处理状态枚举。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Getter
public enum DocStatusEnum {

    PROCESSING("processing", "处理中"),
    PARSING("parsing", "解析中"),
    RETRIEVING("retrieving", "检索中"),
    OPTIMIZING("optimizing", "优化中"),
    READY("ready", "已就绪"),
    FAILED("failed", "处理失败"),
    CANCELLED("cancelled", "已取消");

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
