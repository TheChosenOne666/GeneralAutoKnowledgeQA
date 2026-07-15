package com.xiongda.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 文档批量删除请求体。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class DocumentBatchDeleteRequest implements Serializable {

    /**
     * 要删除的文档 ID 列表
     */
    private List<Long> ids;
}
