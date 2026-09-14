package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.DeptPO;
import org.apache.ibatis.annotations.Mapper;

/** 部门 Mapper。 */
@Mapper
public interface DeptMapper extends BaseMapper<DeptPO> {
}
