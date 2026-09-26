package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.SurveyReportPO;
import org.apache.ibatis.annotations.Mapper;

/** 调研报告 Mapper。 */
@Mapper
public interface SurveyReportMapper extends BaseMapper<SurveyReportPO> {
}
