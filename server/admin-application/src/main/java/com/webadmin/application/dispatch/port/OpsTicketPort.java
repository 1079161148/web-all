package com.webadmin.application.dispatch.port;

import java.util.List;

/**
 * 工单端口（调度演示的后端）。
 *
 * <h3>为什么"抢单"必须由条件 UPDATE 承担</h3>
 * 前端的并发模拟看起来像抢单，但没有数据库行锁参与就不是真的：
 * 两个用户同时点抢单，"先查状态再更新"会让两者都成功。
 * {@code claim} 返回 boolean —— false 即"已被他人抢走"，
 * 由调用方给出明确反馈。语义上等价于 CAS。
 */
public interface OpsTicketPort {

    record OpsTicket(
            long id,
            String title,
            String channel,
            String priority,
            String state,
            String claimer,
            int slaMinutes,
            long createdAt
    ) {
    }

    /** 最近 100 条（惰性 SLA 升级后返回）。 */
    List<OpsTicket> list(long tenantId);

    /** 原子抢单。返回 false = 已被他人抢走。 */
    boolean claim(long id, long tenantId, String claimer);

    /** 办结（仅接单人可操作）。返回 false = 无权或状态不允许。 */
    boolean complete(long id, long tenantId, String claimer);

    /** 放回待处理池（仅接单人可操作，看板拖拽"处理中 → 待处理"）。 */
    boolean release(long id, long tenantId, String claimer);
}
