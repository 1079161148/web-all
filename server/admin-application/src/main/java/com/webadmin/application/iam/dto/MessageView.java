package com.webadmin.application.iam.dto;

import java.time.Instant;

/**
 * 站内信视图（收件箱行）。
 *
 * @param isRead 是否已读。角标与列表的筛选都依赖它
 */
public record MessageView(
        long id,
        String title,
        String content,
        String msgType,
        boolean isRead,
        Instant readTime,
        Instant createTime) {
}
