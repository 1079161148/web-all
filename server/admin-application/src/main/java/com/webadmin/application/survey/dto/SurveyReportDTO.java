package com.webadmin.application.survey.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 调研分析报告读模型（列表不返回 content，同 {@link SurveyPaperDTO}）。 */
public record SurveyReportDTO(
        Long id,
        Long taskId,
        String taskName,
        String reportTitle,
        String reportType,
        String author,
        LocalDate publishDate,
        String summary,
        String content,
        Long fileId,
        String fileName,
        String status,
        String remark,
        Long tenantId,
        Instant createTime) {
}
