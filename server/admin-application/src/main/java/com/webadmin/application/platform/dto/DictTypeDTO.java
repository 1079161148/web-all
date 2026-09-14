package com.webadmin.application.platform.dto;

import java.time.Instant;

/**
 * 字典类型读模型。
 *
 * @param sourceTenantId 该行数据来自哪个租户：{@code 0} 表示平台默认，其余表示租户覆盖。
 *                       列表里带上它，管理员才能一眼分辨"这条是平台定义的还是本租户改的"。
 *                       两级 fallback 的机制如果没有这个可见性，排查"为什么值不对"会非常困难。
 */
public record DictTypeDTO(
        Long id,
        String dictName,
        String dictType,
        String status,
        String remark,
        Long sourceTenantId,
        Instant createTime
) {
}
