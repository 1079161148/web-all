package com.webadmin.application.survey.command;

import java.time.LocalDate;

/**
 * 数据采集新增修改命令。
 *
 * <p>{@code taskName} 不在接口入参里（前端不传）：它由 AppService 按 {@code taskId}
 * 取任务名后填入，作为历史快照保留。
 */
public record SurveyCollectCommand(
        Long taskId,
        String taskName,
        String channel,
        String collector,
        LocalDate collectDate,
        Integer sampleCount,
        Integer validCount,
        Integer qualityScore,
        Long fileId,
        String fileName,
        String status,
        String remark) {
}
