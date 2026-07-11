package com.xiongda.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户更新请求（管理员修改成员）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class UserUpdateRequest implements Serializable {

    private Long id;

    private String role;

    private Integer isActive;
}
