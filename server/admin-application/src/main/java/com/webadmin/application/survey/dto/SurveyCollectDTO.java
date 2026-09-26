package com.webadmin.application.survey.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 数据采集记录读模型。 */
public record SurveyCollectDTO(
        Long id,
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
        String remark,
        Long tenantId,
        Instant createTime) {
}
