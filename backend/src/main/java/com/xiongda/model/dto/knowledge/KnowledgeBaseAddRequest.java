package com.xiongda.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建知识库请求。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class KnowledgeBaseAddRequest implements Serializable {

    private String name;

    private String description;

    /**
     * shared / personal，默认 personal
     */
    private String scope = "personal";
}
