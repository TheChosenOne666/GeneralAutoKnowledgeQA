package com.xiongda.model.dto.knowledge;

import com.xiongda.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库查询请求。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeBaseQueryRequest extends PageRequest {

    /**
     * shared / personal
     */
    private String scope;

    private String name;
}
