package com.webadmin.application.survey.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 调研任务分页查询条件。 */
@Getter
@Setter
public class SurveyTaskPageQuery extends PageQuery {

    /** 任务编码，模糊匹配。 */
    private String taskCode;

    /** 任务名称，模糊匹配。 */
    private String taskName;

    /** 任务类型（字典 srvy_task_type），精确匹配。 */
    private String taskType;

    /** 任务状态（字典 srvy_task_status），精确匹配。 */
    private String status;

    /** 优先级（字典 srvy_priority），精确匹配。 */
    private String priority;
}
