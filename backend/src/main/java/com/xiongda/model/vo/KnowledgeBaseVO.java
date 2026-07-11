package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 知识库视图对象。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class KnowledgeBaseVO implements Serializable {

    private Long id;

    private String name;

    private String description;

    /**
     * shared / personal
     */
    private String scope;

    private Long ownerId;

    private Integer documentCount;

    private Date createTime;
}
