package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.UserPostPO;
import org.apache.ibatis.annotations.Mapper;

/** 用户-岗位关联 Mapper。 */
@Mapper
public interface UserPostMapper extends BaseMapper<UserPostPO> {
}
