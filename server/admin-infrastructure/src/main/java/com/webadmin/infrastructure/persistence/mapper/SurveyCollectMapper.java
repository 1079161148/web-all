package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.SurveyCollectPO;
import org.apache.ibatis.annotations.Mapper;

/** 数据采集 Mapper。 */
@Mapper
public interface SurveyCollectMapper extends BaseMapper<SurveyCollectPO> {
}
