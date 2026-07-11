package com.xiongda.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiongda.model.entity.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息数据库操作。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
