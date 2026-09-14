package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.RoleMenuPO;
import org.apache.ibatis.annotations.Mapper;

/** 角色-菜单关联 Mapper。 */
@Mapper
public interface RoleMenuMapper extends BaseMapper<RoleMenuPO> {
}
