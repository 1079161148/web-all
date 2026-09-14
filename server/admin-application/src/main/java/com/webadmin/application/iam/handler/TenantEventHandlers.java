package com.webadmin.application.iam.handler;

import com.webadmin.domain.iam.event.TenantEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 租户领域事件订阅者。
 *
 * <h3>为什么用 {@code @ApplicationModuleListener} 而不是裸 {@code @EventListener}</h3>
 * 它等价于 {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async}，
 * 并由 Spring Modulith 包装成<b>事务性 Outbox</b>：
 * <ol>
 *   <li>事件先与业务数据在<b>同一事务</b>内写入 {@code event_publication} 表</li>
 *   <li>事务提交后再由处理器消费</li>
 *   <li>处理失败会保留未完成记录，支持重试（因此处理器必须<b>幂等</b>）</li>
 * </ol>
 *
 * <p>用裸 {@code @EventListener} 的话，事件在<b>事务提交前</b>就投递了 ——
 * 一旦事务回滚，缓存已清、通知已发，数据与副作用不一致，而且这种问题极难复现。
 *
 * <h3>为什么每种事件单独一个方法</h3>
 * Modulith 的发布记录按「监听器 + 事件类型」登记。显式声明每个事件类型，
 * 一是让「哪些事件有人关心」一目了然，二是新增事件类型时不会静默漏掉处理。
 * 若写成接收 {@code TenantEvent} 基类型的方法，虽然更短，但可读性会下降。
 *
 * <h3>P1 待补的副作用（当前只记日志）</h3>
 * <ul>
 *   <li>{@code Suspended} / {@code Closed} → 清理该租户的权限缓存 + 强制在线用户下线</li>
 *   <li>{@code Renewed} → 重置配额相关的缓存</li>
 *   <li>{@code Renamed} → 刷新租户元信息缓存</li>
 *   <li>全部事件 → 写入审计日志</li>
 * </ul>
 * 缓存与在线用户能力尚未实现，因此现在只输出日志。刻意不做「假装清缓存」的空调用 ——
 * 那会让人误以为副作用已经生效。
 */
@Slf4j
@Component
public class TenantEventHandlers {

    /** 租户创建：此时状态为 PENDING，尚未可用。 */
    @ApplicationModuleListener
    void on(TenantEvent.Created event) {
        log.info("[租户事件] 创建 tenantId={} code={} plan={}",
                event.tenantId().value(), event.tenantCode(), event.planCode());
    }

    /** 租户激活：开始可用。 */
    @ApplicationModuleListener
    void on(TenantEvent.Activated event) {
        log.info("[租户事件] 激活 tenantId={} code={}",
                event.tenantId().value(), event.tenantCode());
    }

    /** 租户暂停：数据保留但拒绝访问。P1 需在此清缓存并踢下线。 */
    @ApplicationModuleListener
    void on(TenantEvent.Suspended event) {
        log.warn("[租户事件] 暂停 tenantId={} code={} reason={} —— P1 需清理权限缓存并强制下线",
                event.tenantId().value(), event.tenantCode(), event.reason());
    }

    /** 租户续期：配额与到期时间已变，缓存需刷新。 */
    @ApplicationModuleListener
    void on(TenantEvent.Renewed event) {
        log.info("[租户事件] 续期 tenantId={} code={} plan={} 新到期时间={}",
                event.tenantId().value(), event.tenantCode(), event.planCode(), event.newExpireTime());
    }

    /** 租户过期：由定时任务批量推进时产生。 */
    @ApplicationModuleListener
    void on(TenantEvent.Expired event) {
        log.warn("[租户事件] 过期 tenantId={} code={}",
                event.tenantId().value(), event.tenantCode());
    }

    /** 租户关闭：终态。 */
    @ApplicationModuleListener
    void on(TenantEvent.Closed event) {
        log.warn("[租户事件] 关闭 tenantId={} code={} reason={}",
                event.tenantId().value(), event.tenantCode(), event.reason());
    }

    /** 租户改名：仅影响展示。 */
    @ApplicationModuleListener
    void on(TenantEvent.Renamed event) {
        log.info("[租户事件] 改名 tenantId={} code={} 新名称={}",
                event.tenantId().value(), event.tenantCode(), event.newName());
    }
}
