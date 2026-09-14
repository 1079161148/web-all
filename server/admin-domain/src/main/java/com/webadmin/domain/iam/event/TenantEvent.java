package com.webadmin.domain.iam.event;

import com.webadmin.domain.shared.DomainEvent;
import com.webadmin.domain.shared.TenantId;
import java.time.Instant;

/**
 * 租户领域事件家族。
 *
 * <p>用 sealed interface + record 表达事件，便于在处理器侧用 switch 模式匹配穷尽所有分支
 * （JDK 21+ 支持 sealed 类型 + 记录模式匹配）。
 *
 * <p>典型订阅方（设计文档 §4.7）：
 * <ul>
 *   <li>权限缓存失效（{@code Suspended} / {@code Closed}）</li>
 *   <li>在线用户强制下线（{@code Suspended} / {@code Closed}）</li>
 *   <li>审计日志记录（全部事件）</li>
 *   <li>租户配额重置（{@code Renewed}）</li>
 * </ul>
 */
public sealed interface TenantEvent extends DomainEvent
        permits TenantEvent.Created,
                TenantEvent.Activated,
                TenantEvent.Suspended,
                TenantEvent.Renewed,
                TenantEvent.Expired,
                TenantEvent.Closed,
                TenantEvent.Renamed {

    TenantId tenantId();

    String tenantCode();

    @Override
    Instant occurredAt();

    /** 租户创建完成。此时状态为 PENDING，尚未可用。 */
    record Created(TenantId tenantId, String tenantCode, String planCode, Instant occurredAt)
            implements TenantEvent {
    }

    /** 租户激活，开始可用。 */
    record Activated(TenantId tenantId, String tenantCode, Instant occurredAt)
            implements TenantEvent {
    }

    /** 租户被暂停（欠费 / 违规），需要清缓存并踢下线。 */
    record Suspended(TenantId tenantId, String tenantCode, String reason, Instant occurredAt)
            implements TenantEvent {
    }

    /** 租户续期成功，配额已重置。 */
    record Renewed(TenantId tenantId, String tenantCode, String planCode, Instant newExpireTime, Instant occurredAt)
            implements TenantEvent {
    }

    /** 租户到期（由定时任务批量推进）。 */
    record Expired(TenantId tenantId, String tenantCode, Instant occurredAt)
            implements TenantEvent {
    }

    /** 租户关闭（终态）。 */
    record Closed(TenantId tenantId, String tenantCode, String reason, Instant occurredAt)
            implements TenantEvent {
    }

    /** 租户改名（仅影响展示，通常只需刷新缓存）。 */
    record Renamed(TenantId tenantId, String tenantCode, String newName, Instant occurredAt)
            implements TenantEvent {
    }
}
