package com.xiongda.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiongda.model.entity.ChatAttachment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问答附件数据库操作。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {
}
