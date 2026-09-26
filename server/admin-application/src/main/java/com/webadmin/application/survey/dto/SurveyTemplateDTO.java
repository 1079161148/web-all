package com.webadmin.application.survey.dto;

import java.time.Instant;

/** 调研模板读模型。 */
public record SurveyTemplateDTO(
        Long id,
        String templateCode,
        String templateName,
        String category,
        String content,
        Integer usageCount,
        String status,
        String remark,
        Long tenantId,
        Instant createTime) {
}
