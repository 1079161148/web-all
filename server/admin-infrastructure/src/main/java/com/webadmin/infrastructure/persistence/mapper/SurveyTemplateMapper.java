package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.SurveyTemplatePO;
import org.apache.ibatis.annotations.Mapper;

/** 调研模板 Mapper。 */
@Mapper
public interface SurveyTemplateMapper extends BaseMapper<SurveyTemplatePO> {
}
