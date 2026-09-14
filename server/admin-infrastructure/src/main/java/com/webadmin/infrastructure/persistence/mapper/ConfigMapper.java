package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.ConfigPO;
import org.apache.ibatis.annotations.Mapper;

/** 参数配置 Mapper。 */
@Mapper
public interface ConfigMapper extends BaseMapper<ConfigPO> {
}
