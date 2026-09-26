package com.webadmin.application.survey.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 数据采集分页查询条件。 */
@Getter
@Setter
public class SurveyCollectPageQuery extends PageQuery {

    /** 所属任务 ID，精确匹配（列表按任务下钻）。 */
    private Long taskId;

    /** 采集人，模糊匹配。 */
    private String collector;

    /** 采集渠道（字典 srvy_channel），精确匹配。 */
    private String channel;

    /** 状态（字典 srvy_collect_status），精确匹配。 */
    private String status;
}
