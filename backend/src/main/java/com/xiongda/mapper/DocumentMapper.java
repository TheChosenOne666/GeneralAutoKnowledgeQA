package com.xiongda.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiongda.model.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档数据库操作。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
