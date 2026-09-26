package com.webadmin.application.survey.command;

/** 问卷 / 提纲新增修改命令。 */
public record SurveyPaperCommand(
        String paperCode,
        String title,
        String paperType,
        Integer versionNo,
        String content,
        String status,
        String remark) {
}
