package com.xiongda.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 移除成员请求（软删除）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class UserRemoveRequest implements Serializable {

    private Long id;
}
