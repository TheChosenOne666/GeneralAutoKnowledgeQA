package com.xiongda.model.enums;

import lombok.Getter;
import org.apache.commons.lang3.ObjectUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户角色枚举。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Getter
public enum UserRoleEnum {

    MEMBER("member", "普通成员"),
    TENANT_ADMIN("tenant_admin", "租户管理员"),
    SUPER_ADMIN("super_admin", "平台超管");

    private final String value;

    private final String text;

    UserRoleEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据 value 获取枚举。
     */
    public static UserRoleEnum getEnumByValue(String value) {
        if (ObjectUtils.isEmpty(value)) {
            return null;
        }
        for (UserRoleEnum userRoleEnum : UserRoleEnum.values()) {
            if (userRoleEnum.value.equals(value)) {
                return userRoleEnum;
            }
        }
        return null;
    }

    /**
     * 获取所有角色值列表。
     */
    public static List<String> getValues() {
        return Arrays.stream(values()).map(UserRoleEnum::getValue).collect(Collectors.toList());
    }
}
