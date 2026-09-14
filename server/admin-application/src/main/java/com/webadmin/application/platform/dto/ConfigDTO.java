package com.webadmin.application.platform.dto;

import java.time.Instant;

/**
 * 参数配置读模型。
 *
 * @param builtin 是否系统内置。带在 DTO 上是必要的：前端据此禁用"删除"按钮，
 *                而不是让用户点了才被拒绝。<b>把限制提前变成可见状态，比事后报错友好。</b>
 */
public record ConfigDTO(
        Long id,
        String configName,
        String configKey,
        String configValue,
        Boolean builtin,
        String remark,
        Long sourceTenantId,
        Instant createTime
) {
}
