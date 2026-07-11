package com.xiongda.model.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 邀请成员请求。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class UserInviteRequest implements Serializable {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String name;

    private String role = "member";
}
