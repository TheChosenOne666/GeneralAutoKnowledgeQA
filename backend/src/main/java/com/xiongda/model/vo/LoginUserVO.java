package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录用户视图对象（含 token）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class LoginUserVO implements Serializable {

    private Long id;

    private String name;

    private String email;

    private String role;

    private Long tenantId;

    private String avatarUrl;

    /**
     * JWT Token
     */
    private String token;
}
