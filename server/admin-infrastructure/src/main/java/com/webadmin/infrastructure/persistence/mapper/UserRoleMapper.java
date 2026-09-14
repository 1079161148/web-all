package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.UserRolePO;
import org.apache.ibatis.annotations.Mapper;

/** 用户-角色关联 Mapper。 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRolePO> {
}
