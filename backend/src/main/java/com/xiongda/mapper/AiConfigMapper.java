package com.xiongda.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiongda.model.entity.AiConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 配置数据库操作。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfig> {
}
