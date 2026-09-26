package com.webadmin.application.survey.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 调研报告分页查询条件。 */
@Getter
@Setter
public class SurveyReportPageQuery extends PageQuery {

    /** 所属任务 ID，精确匹配。 */
    private Long taskId;

    /** 报告标题，模糊匹配。 */
    private String reportTitle;

    /** 报告类型（字典 srvy_report_type），精确匹配。 */
    private String reportType;

    /** 状态（字典 srvy_report_status），精确匹配。 */
    private String status;
}
