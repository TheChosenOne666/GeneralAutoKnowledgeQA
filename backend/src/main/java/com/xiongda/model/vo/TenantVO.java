package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 租户视图对象 — 含实时成员数 / 文档数统计。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class TenantVO implements Serializable {

    private Long id;

    private String name;

    private String slug;

    /**
     * active / suspended
     */
    private String status;

    private Integer maxMembers;

    private Integer maxDocuments;

    private Long memberCount;

    private Long docCount;

    private Date createTime;
}
