package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.MenuPO;
import org.apache.ibatis.annotations.Mapper;

/** 菜单 Mapper。 */
@Mapper
public interface MenuMapper extends BaseMapper<MenuPO> {
}
