package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 邀请链接详情（供注册页预填/展示）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class InviteInfoVO implements Serializable {

    /**
     * 邀请人姓名
     */
    private String inviterName;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 加入后的角色
     */
    private String role;
}
