package com.webadmin.domain.shared;

import java.time.Instant;

/**
 * 领域事件标记接口。
 *
 * <p>设计约束（设计文档 §4.7）：
 * <ul>
 *   <li>事件由聚合根在业务动作中产出，<b>不在 AppService 里"顺手清缓存"</b></li>
 *   <li>由 AppService 通过 {@code ApplicationEventPublisher} 发布；
 *       Spring Modulith 会将其包装为<b>事务性 Outbox</b>，保证「业务落库成功才投递」</li>
 *   <li>禁止用裸 {@code @EventListener} 处理有副作用的逻辑（事务回滚会导致缓存已清、消息已发）</li>
 *   <li>事件处理器必须<b>幂等</b>（投递语义为 at-least-once）</li>
 * </ul>
 */
public interface DomainEvent {

    /** 事件发生时间。由聚合通过注入的 {@link java.time.Clock} 提供，保证可测试。 */
    Instant occurredAt();
}
