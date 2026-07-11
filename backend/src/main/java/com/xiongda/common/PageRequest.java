package com.xiongda.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用分页请求基类。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class PageRequest implements Serializable {

    /**
     * 当前页号
     */
    private long current = 1;

    /**
     * 页面大小
     */
    private long pageSize = 10;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序顺序（asc / desc）
     */
    private String sortOrder;
}
