package com.webadmin.domain.iam.event;

import com.webadmin.domain.shared.DomainEvent;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.domain.shared.UserId;
import java.time.Instant;

/**
 * 用户领域事件家族（sealed interface）。
 *
 * <h3>为什么用 sealed</h3>
 * 与 {@code TenantEvent} 同理，但这里多一层实际收益：
 * 事件处理器里对用户事件做模式匹配 switch 时<b>不需要 default 分支</b>，
 * 将来新增一个事件类型，<b>所有 switch 会编译失败</b>，强制作者确认
 * "这个新事件需要被哪些处理器感知"。遗漏一个缓存失效往往就是线上数据不一致的根因，
 * 而它本该在编译期被拦住。
 *
 * <h3>命名与粒度</h3>
 * 事件名用<b>过去时</b>（Created / PasswordChanged）—— 事件描述"已经发生的事实"，
 * 不是"要执行的命令"。粒度判据：<b>如果两个变更会触发完全相同的下游反应，
 * 就合并为一个事件。</b>例如激活/停用/锁定都归结为 {@code StatusChanged}，
 * 因为下游（清缓存、踢下线）处理完全一样，只是新状态值不同。
 */
public sealed interface UserEvent extends DomainEvent
        permits UserEvent.Created,
                UserEvent.PasswordChanged,
                UserEvent.StatusChanged,
                UserEvent.RolesChanged,
                UserEvent.LoggedIn,
                UserEvent.Deleted {

    TenantId tenantId();

    UserId userId();

    String username();

    @Override
    Instant occurredAt();

    /** 用户创建：此时尚未分配角色。 */
    record Created(TenantId tenantId, UserId userId, String username, Instant occurredAt)
            implements UserEvent {
    }

    /** 密码变更（含管理员重置）：必须使该用户所有在线令牌失效。 */
    record PasswordChanged(TenantId tenantId, UserId userId, String username,
                           boolean resetByAdmin, Instant occurredAt)
            implements UserEvent {
    }

    /** 状态变更：激活 / 停用 / 锁定 / 解锁的统一表达。 */
    record StatusChanged(TenantId tenantId, UserId userId, String username,
                         String previousStatus, String currentStatus, String reason,
                         Instant occurredAt)
            implements UserEvent {
    }

    /** 角色分配变更：必须使该用户的权限缓存失效。 */
    record RolesChanged(TenantId tenantId, UserId userId, String username,
                        int roleCount, Instant occurredAt)
            implements UserEvent {
    }

    /** 登录成功：用于登录审计与"最近登录"展示。 */
    record LoggedIn(TenantId tenantId, UserId userId, String username,
                    String loginIp, Instant occurredAt)
            implements UserEvent {
    }

    /** 用户删除（逻辑删除）：需要清理其关联与缓存。 */
    record Deleted(TenantId tenantId, UserId userId, String username, Instant occurredAt)
            implements UserEvent {
    }
}
