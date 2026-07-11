package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户视图对象（脱敏，不含密码）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class UserVO implements Serializable {

    private Long id;

    private String name;

    private String email;

    private String role;

    private Long tenantId;

    private String avatarUrl;

    private Integer isActive;

    private Date lastActiveAt;

    private Date createTime;
}
