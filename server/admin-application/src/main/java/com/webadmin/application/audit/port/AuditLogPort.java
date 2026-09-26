package com.webadmin.application.audit.port;

import com.webadmin.common.api.PageResult;

import java.time.Instant;

/**
 * 操作审计端口。
 *
 * <h3>审计为什么经事件而不是在业务方法里直接写表</h3>
 * 审计是横切关注点：写在业务方法里，方法体膨胀且"写业务"与"写审计"
 * 会因一个抛错而搅在一起。发布领域事件（事务性 Outbox）后：
 * <ul>
 *   <li>事件与业务数据<b>同事务</b>落库 —— 业务成功则审计必达</li>
 *   <li>消费者失败可重试（处理器必须幂等：按 biz 唯一键去重或接受重复）</li>
 *   <li>审计存储的选型（表 / ES / 冷存）与业务完全解耦</li>
 * </ul>
 */
public interface AuditLogPort {

    record AuditLogEntry(
            long id,
            long tenantId,
            long userId,
            String username,
            String action,
            String bizType,
            String bizId,
            String summary,
            String diffText,
            Instant createTime
    ) {
    }

    void record(AuditLogEntry entry);

    PageResult<AuditLogEntry> page(long tenantId, int page, int size);
}
