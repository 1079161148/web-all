package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.PostPO;
import org.apache.ibatis.annotations.Mapper;

/** 岗位 Mapper。 */
@Mapper
public interface PostMapper extends BaseMapper<PostPO> {
}
