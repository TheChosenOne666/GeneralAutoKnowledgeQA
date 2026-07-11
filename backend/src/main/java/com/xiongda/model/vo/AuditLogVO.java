package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志视图对象。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class AuditLogVO implements Serializable {

    private Long id;

    private String userEmail;

    private String action;

    private String resourceType;

    private String resourceId;

    private String detail;

    private String ipAddress;

    private Date createTime;
}
