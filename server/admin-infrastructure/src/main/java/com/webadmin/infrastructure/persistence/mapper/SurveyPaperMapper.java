package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.SurveyPaperPO;
import org.apache.ibatis.annotations.Mapper;

/** 问卷 / 提纲 Mapper。 */
@Mapper
public interface SurveyPaperMapper extends BaseMapper<SurveyPaperPO> {
}
