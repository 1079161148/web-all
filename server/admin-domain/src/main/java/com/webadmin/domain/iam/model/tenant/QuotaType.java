package com.webadmin.domain.iam.model.tenant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 配额维度。
 *
 * <p>新增维度时只需在此登记，{@link Quota} 的扣减逻辑无需改动分支结构。
 */
@Getter
@RequiredArgsConstructor
public enum QuotaType {

    /** 可创建的用户数。 */
    USER("用户数"),

    /** 可使用的存储空间（字节）。 */
    STORAGE_BYTES("存储空间"),

    /** 每月可调用的 API 次数。 */
    API_CALLS_PER_MONTH("月度 API 调用量"),
    ;

    private final String description;
}
