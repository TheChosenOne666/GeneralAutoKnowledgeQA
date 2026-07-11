package com.xiongda.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiongda.model.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户数据库操作。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
