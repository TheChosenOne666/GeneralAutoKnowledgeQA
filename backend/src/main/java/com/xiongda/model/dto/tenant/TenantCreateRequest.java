package com.xiongda.model.dto.tenant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建租户请求 — 平台超管指定管理员邮箱，将其设为新租户首个租户管理员。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class TenantCreateRequest implements Serializable {

    @NotBlank(message = "租户名称不能为空")
    private String name;

    @NotBlank(message = "租户标识不能为空")
    private String slug;

    /**
     * 成员数上限（null 时取默认 50）；<=0 视为不限。
     */
    private Integer maxMembers;

    /**
     * 文档数上限（null 时取默认 1000）；<=0 视为不限。
     */
    private Integer maxDocuments;

    @NotBlank(message = "管理员邮箱不能为空")
    @Email(message = "管理员邮箱格式不正确")
    private String adminEmail;
}
