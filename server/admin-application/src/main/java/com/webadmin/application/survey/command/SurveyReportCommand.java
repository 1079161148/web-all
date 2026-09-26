package com.webadmin.application.survey.command;

import java.time.LocalDate;

/**
 * 调研报告新增修改命令。
 *
 * <p>{@code taskName} 同 {@link SurveyCollectCommand}：由应用层按 taskId 快照填入，前端不传。
 */
public record SurveyReportCommand(
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
        String remark) {
}
