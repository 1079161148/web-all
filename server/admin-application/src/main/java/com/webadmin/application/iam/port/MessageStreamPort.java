package com.webadmin.application.iam.port;

import java.time.Duration;

/**
 * 消息实时推送通道（SSE + 多实例广播）。
 *
 * <h3>SSE 的鉴权：短时一次性票据</h3>
 * {@code EventSource} 不能携带自定义请求头（浏览器限制），
 * 所以"带 Bearer 令牌"这条路走不通，而把长效令牌放进查询串
 * 等于把它写进访问日志与代理日志 —— 不可接受。
 * 解法是<b>票据换连接</b>：已认证用户先调 {@link #issueTicket} 换一张
 * 30 秒、一次性、绑定身份的短票，再用它开流。
 * 即便票据泄漏，30 秒后作废、且用过即焚（{@link #consumeTicket} 取出即删除）。
 *
 * <h3>多实例广播：Redis Pub/Sub</h3>
 * SSE 连接是<b>本实例内存状态</b>：用户 A 连在实例 1 上，
 * 实例 2 发的公告必须跨实例送达。发布/订阅一条轻量通知
 * （只有租户与用户 ID，不含消息体），各实例收到后把事件写给本地连接。
 * 前端收到事件后<b>重新拉取未读数与列表</b> —— 消息体不经由推送通道传输，
 * 推送只是"该刷新了"的信号：单一事实来源永远在数据库，
 * 也避免了"推送丢失导致状态永久不一致"这类问题（丢一次推送只是晚几秒刷新）。
 *
 * <h3>为什么连接的注册表在实现内部</h3>
 * {@code SseEmitter} 是 web 层类型，不应出现在应用层的端口签名里，
 * 因此用 {@link StreamConnection}（发送/关闭两个动作）做隔离 ——
 * 连接由控制器创建并包装，本端口只负责"登记、投递、清理"。
 */
public interface MessageStreamPort {

    /**
     * 签发 SSE 连接票据。
     *
     * @return 一次性票据（短时间内有效，见实现）
     */
    String issueTicket(long tenantId, long userId);

    /**
     * 消费票据：取出并<b>立即删除</b>（用过即焚）。
     *
     * @return 票据绑定的身份；票据无效 / 过期 / 已使用时返回 null
     */
    TicketOwner consumeTicket(String ticket);

    /**
     * 登记一条 SSE 连接。
     *
     * @return 注销句柄：连接关闭/超时/出错时调用，保证注册表不残留死连接
     */
    Runnable open(long tenantId, long userId, StreamConnection connection);

    /**
     * 向某用户的所有在线连接广播一条"有新消息"信号（跨实例）。
     */
    void notifyUser(long tenantId, long userId);

    /**
     * 连接的抽象（隔离 web 层类型，见类注释）。
     *
     * <p>{@code send} 返回 false 表示发送失败（连接已死）——
     * 实现方应把死连接从注册表移除。
     */
    interface StreamConnection {
        boolean send(String payload);

        void close();
    }

    /** 票据绑定的身份。 */
    record TicketOwner(long tenantId, long userId) {
    }

    /** 便捷构造：默认有效期由实现决定。 */
    static Duration defaultTicketTtl() {
        return Duration.ofSeconds(30);
    }
}
