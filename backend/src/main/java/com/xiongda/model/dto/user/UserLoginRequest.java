package com.xiongda.model.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户登录请求。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class UserLoginRequest implements Serializable {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "密码不能为空")
    private String userPassword;
}
