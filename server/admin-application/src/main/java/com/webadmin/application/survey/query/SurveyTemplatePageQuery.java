package com.webadmin.application.survey.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 调研模板分页查询条件。 */
@Getter
@Setter
public class SurveyTemplatePageQuery extends PageQuery {

    /** 模板编码，模糊匹配。 */
    private String templateCode;

    /** 模板名称，模糊匹配。 */
    private String templateName;

    /** 分类（字典 srvy_template_category），精确匹配。 */
    private String category;

    /** 状态（字典 sys_status），精确匹配。 */
    private String status;
}
