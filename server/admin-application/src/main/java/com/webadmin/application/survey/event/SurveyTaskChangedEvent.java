package com.webadmin.application.survey.event;

import com.webadmin.application.survey.dto.SurveyTaskDTO;

/**
 * 调研任务变更事件（审计消费）。
 *
 * <p>before/after 都是<b>完整读模型快照</b>：diff 的计算交给消费者，
 * 发布方只负责"把前后两份事实带出来"。缺 before 表示新建。
 */
public record SurveyTaskChangedEvent(
        String action,
        SurveyTaskDTO before,
        SurveyTaskDTO after,
        long tenantId,
        long userId,
        String username
) {
}
