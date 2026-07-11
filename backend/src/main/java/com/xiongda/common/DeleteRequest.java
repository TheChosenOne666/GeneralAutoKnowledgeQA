package com.xiongda.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用删除请求体。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class DeleteRequest implements Serializable {

    /**
     * 要删除的记录 ID
     */
    private Long id;
}
