package com.xiongda.model.dto.tenant;

import lombok.Data;

import java.io.Serializable;

/**
 * 租户配额设置请求。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class TenantQuotaRequest implements Serializable {

    /**
     * 成员数上限；<=0 视为不限。
     */
    private Integer maxMembers;

    /**
     * 文档数上限；<=0 视为不限。
     */
    private Integer maxDocuments;
}
