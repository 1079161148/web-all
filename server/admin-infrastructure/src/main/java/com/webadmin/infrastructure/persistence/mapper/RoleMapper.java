package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.RolePO;
import org.apache.ibatis.annotations.Mapper;

/** 角色 Mapper。 */
@Mapper
public interface RoleMapper extends BaseMapper<RolePO> {
}
