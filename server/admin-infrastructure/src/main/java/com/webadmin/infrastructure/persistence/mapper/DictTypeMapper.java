package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.DictTypePO;
import org.apache.ibatis.annotations.Mapper;

/** 字典类型 Mapper。 */
@Mapper
public interface DictTypeMapper extends BaseMapper<DictTypePO> {
}
