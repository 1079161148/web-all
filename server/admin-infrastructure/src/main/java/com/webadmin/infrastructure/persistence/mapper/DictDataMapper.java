package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.DictDataPO;
import org.apache.ibatis.annotations.Mapper;

/** 字典数据 Mapper。 */
@Mapper
public interface DictDataMapper extends BaseMapper<DictDataPO> {
}
