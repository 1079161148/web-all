package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.shared.TenantId;

/**
 * 租户 ID 生成端口（Port）。
 *
 * <p>这是<b>依赖倒置</b>的示范（设计文档 §4.3）：领域层只声明「需要一个 ID 生成能力」，
 * 具体实现（雪花算法 / 号段 / 数据库序列）放在 infrastructure 层。
 * 领域层因此不必引入任何 ID 生成框架。
 */
@FunctionalInterface
public interface TenantIdGenerator {

    /** 生成全局唯一、趋势递增的租户 ID。 */
    TenantId nextId();
}
