package com.webadmin.application.survey.dto;

import java.time.Instant;

/**
 * 问卷 / 提纲读模型。
 *
 * <p>{@code content} 是富文本 HTML。列表接口<b>不返回它</b>（见 Port 的分页实现），
 * 否则一页 20 条会把整页正文一起传回来 —— 正文动辄几十 KB，是列表接口最容易踩的坑。
 * 需要正文时走详情接口。
 */
public record SurveyPaperDTO(
        Long id,
        String paperCode,
        String title,
        String paperType,
        Integer versionNo,
        String content,
        String status,
        String remark,
        Long tenantId,
        Instant createTime) {
}
