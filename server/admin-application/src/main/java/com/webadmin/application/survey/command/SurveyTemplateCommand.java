package com.webadmin.application.survey.command;

/** 调研模板新增修改命令。 */
public record SurveyTemplateCommand(
        String templateCode,
        String templateName,
        String category,
        String content,
        Integer usageCount,
        String status,
        String remark) {
}
