package com.xiongda.model.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 接受邀请注册请求（公开，无需登录）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class UserAcceptInviteRequest implements Serializable {

    @NotBlank(message = "邀请令牌不能为空")
    private String token;

    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "密码不能为空")
    private String userPassword;
}
