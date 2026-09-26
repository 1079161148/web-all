package com.webadmin.application.survey.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 调研任务读模型。 */
public record SurveyTaskDTO(
        Long id,
        String taskCode,
        String taskName,
        String taskType,
        String priority,
        String ownerName,
        Long deptId,
        LocalDate startDate,
        LocalDate endDate,
        Integer progress,
        String status,
        String remark,
        Long tenantId,
        Instant createTime) {
}
