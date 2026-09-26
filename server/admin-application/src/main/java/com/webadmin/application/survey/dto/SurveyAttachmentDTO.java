package com.webadmin.application.survey.dto;

import java.time.Instant;

/**
 * 附件读模型。
 *
 * <p>{@code url} 由应用层拼出（{@code /api/v1/survey/files/{id}/content}），
 * 不让前端各自拼路径 —— 换下载实现（如签名 URL）时只改一处。
 */
public record SurveyAttachmentDTO(
        Long id,
        String bizType,
        Long bizId,
        String fileName,
        String filePath,
        Long fileSize,
        String contentType,
        String storageType,
        String url,
        Instant createTime) {
}
