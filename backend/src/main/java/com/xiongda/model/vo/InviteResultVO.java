package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 生成邀请链接结果。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class InviteResultVO implements Serializable {

    /**
     * 邀请令牌
     */
    private String token;

    /**
     * 可复制的完整邀请链接
     */
    private String inviteUrl;

    /**
     * 加入后的角色
     */
    private String role;

    /**
     * 过期时间
     */
    private Date expiresAt;
}
