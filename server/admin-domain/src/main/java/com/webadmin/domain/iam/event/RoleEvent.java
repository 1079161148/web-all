package com.webadmin.domain.iam.event;

import com.webadmin.domain.shared.DomainEvent;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import java.time.Instant;

/**
 * 角色领域事件家族（sealed interface）。
 *
 * <h3>为什么 {@code PermissionChanged} 要携带足够多的信息</h3>
 * 角色权限变更时，下游需要失效的是该角色下<b>所有用户</b>的权限缓存。
 * 事件里带上 {@code menuCount} / {@code dataScopeDeptCount} / {@code dataScope}，
 * 是为了让日志与监控能直接回答"这次变更把权限放宽了还是收窄了、影响面多大" ——
 * 而不用再回查数据库。权限放宽是最需要被审计的动作，
 * 让它在事件层面就带上量化信息，排查"为什么他突然能看到别的部门数据了"时能省大量时间。
 */
public sealed interface RoleEvent extends DomainEvent
        permits RoleEvent.Created,
                RoleEvent.PermissionChanged,
                RoleEvent.Updated,
                RoleEvent.StatusChanged,
                RoleEvent.Deleted {

    TenantId tenantId();

    RoleId roleId();

    String roleKey();

    @Override
    Instant occurredAt();

    record Created(TenantId tenantId, RoleId roleId, String roleKey, String roleName,
                   Instant occurredAt)
            implements RoleEvent {
    }

    /** 菜单权限或数据范围变更：需要失效所有关联用户的权限缓存。 */
    record PermissionChanged(TenantId tenantId, RoleId roleId, String roleKey,
                             int menuCount, int dataScopeDeptCount, String dataScope,
                             Instant occurredAt)
            implements RoleEvent {
    }

    /** 基础信息变更（名称、排序）：仅影响展示。 */
    record Updated(TenantId tenantId, RoleId roleId, String roleKey, Instant occurredAt)
            implements RoleEvent {
    }

    record StatusChanged(TenantId tenantId, RoleId roleId, String roleKey,
                         String currentStatus, Instant occurredAt)
            implements RoleEvent {
    }

    record Deleted(TenantId tenantId, RoleId roleId, String roleKey, Instant occurredAt)
            implements RoleEvent {
    }
}
