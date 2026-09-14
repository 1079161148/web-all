package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.RoleDeptPO;
import org.apache.ibatis.annotations.Mapper;

/** 角色-部门关联 Mapper（自定义数据范围）。 */
@Mapper
public interface RoleDeptMapper extends BaseMapper<RoleDeptPO> {
}
