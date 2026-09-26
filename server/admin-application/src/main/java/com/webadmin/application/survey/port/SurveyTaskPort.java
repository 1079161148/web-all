package com.webadmin.application.survey.port;

import com.webadmin.application.survey.command.SurveyTaskCommand;
import com.webadmin.application.survey.dto.SurveyTaskDTO;
import com.webadmin.application.survey.query.SurveyTaskPageQuery;
import com.webadmin.common.api.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * 调研任务读写端口。
 *
 * <p>普通业务表（{@code srvy_task} 不在租户拦截器忽略名单里），
 * 因此实现<b>不</b>手写 {@code tenant_id} 条件 —— 拦截器会自动施加。
 */
public interface SurveyTaskPort {

    PageResult<SurveyTaskDTO> page(SurveyTaskPageQuery query);

    Optional<SurveyTaskDTO> findById(long id);

    /**
     * 全部任务（用于采集/报告表单的"所属任务"下拉）。
     *
     * <p>为什么不给通用分页：下拉需要的是"可选的完整集合"，
     * 让页面自己翻页取全量反而会用错（漏掉后几页）。
     */
    List<SurveyTaskDTO> listAll();

    boolean codeExists(String taskCode, Long excludeId);

    Long insert(SurveyTaskCommand command);

    void update(long id, SurveyTaskCommand command);

    void delete(long id);
}
