package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.SurveyAttachmentPO;
import org.apache.ibatis.annotations.Mapper;

/** 附件元数据 Mapper。 */
@Mapper
public interface SurveyAttachmentMapper extends BaseMapper<SurveyAttachmentPO> {
}
