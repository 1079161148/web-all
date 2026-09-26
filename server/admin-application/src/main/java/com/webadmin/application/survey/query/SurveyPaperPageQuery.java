package com.webadmin.application.survey.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 问卷 / 提纲分页查询条件。 */
@Getter
@Setter
public class SurveyPaperPageQuery extends PageQuery {

    /** 编码，模糊匹配。 */
    private String paperCode;

    /** 标题，模糊匹配。 */
    private String title;

    /** 类型（字典 srvy_paper_type），精确匹配。 */
    private String paperType;

    /** 状态（字典 srvy_paper_status），精确匹配。 */
    private String status;
}
