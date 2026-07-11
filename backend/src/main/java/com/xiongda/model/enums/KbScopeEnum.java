package com.xiongda.model.enums;

import lombok.Getter;

/**
 * 知识库作用域枚举。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Getter
public enum KbScopeEnum {

    SHARED("shared", "共享知识库"),
    PERSONAL("personal", "个人知识库");

    private final String value;

    private final String text;

    KbScopeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static KbScopeEnum getEnumByValue(String value) {
        for (KbScopeEnum scope : KbScopeEnum.values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        return null;
    }
}
