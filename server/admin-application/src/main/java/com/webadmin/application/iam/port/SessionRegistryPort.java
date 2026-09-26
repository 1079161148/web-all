package com.webadmin.application.iam.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 在线会话注册表（Redis）。
 *
 * <h3>它同时承担三件事，这是刻意合一的</h3>
 * <ol>
 *   <li><b>令牌吊销（黑名单）</b>：会话键存在 = 令牌有效。
 *       注销/踢人 = 删除该键 —— <b>"不在注册表里"就是"已吊销"</b>。
 *       这样就不需要再维护一份黑名单（黑名单还要设 TTL 兜底、
 *       还要担心"名单条目比令牌活得久"这类问题）</li>
 *   <li><b>在线会话列表</b>：注册表本身就是列表的数据来源（设备/IP/最后活跃）</li>
 *   <li><b>刷新令牌的归属</b>：刷新令牌挂在会话下（见 {@link RefreshTokenPort} 的 family 概念），
 *       注销会话即连带失效其刷新令牌</li>
 * </ol>
 *
 * <h3>与"令牌版本号"的分工（两者缺一不可）</h3>
 * <table>
 *   <tr><th></th><th>版本号（库里的列）</th><th>会话注册表（本端口）</th></tr>
 *   <tr><td>粒度</td><td>用户级 —— 一次吊销该用户全部令牌</td><td>会话级 —— 可只踢某一台设备</td></tr>
 *   <tr><td>持久性</td><td><b>durable</b>（存库，Redis 丢失也生效）</td><td>易失（Redis 丢失即全部失效）</td></tr>
 *   <tr><td>用途</td><td>改密码 / 停用 / 强制下线</td><td>注销当前设备 / 在线列表 / 定向踢人</td></tr>
 * </table>
 * 因此校验顺序是：<b>先校验版本号（便宜、durable），再校验会话是否还在</b>。
 * Redis 不可用时降级为"只校验版本号" —— 粗粒度吊销仍然成立，
 * 丢掉的是"定向踢人"这种细粒度能力，而不是整个吊销能力。
 *
 * <h3>TTL 语义</h3>
 * 会话 TTL 应覆盖"刷新令牌的整个生命周期"（默认 7 天，见 {@code RefreshTokenPort}），
 * 而不是访问令牌的 30 分钟：会话代表的是"这台设备还处于登录状态"，
 * 访问令牌只是它的短期凭证。若按访问令牌设 TTL，在线会话列表会在 30 分钟后
 * 把仍有刷新能力的会话全部丢掉。
 */
public interface SessionRegistryPort {

    /** 注册一个会话（登录成功时调用）。 */
    void register(SessionRecord session, Duration ttl);

    /**
     * 查找会话（<b>鉴权热路径</b>）。
     *
     * <p>返回空表示"该会话已不存在"—— 已注销、已被踢、或已过期。
     * 实现必须是**一次 Redis 读**：本方法在<b>每个请求</b>上都会执行。
     */
    Optional<SessionRecord> find(long tenantId, long userId, String sessionId);

    /**
     * 刷新"最后活跃时间"。
     *
     * <p>刻意与 {@link #find} 分开：热路径上不需要写 Redis。
     * 实现方可以按时间窗口节流（例如 60 秒内只写一次），
     * 否则每个请求一次写会把 Redis 变成瓶颈。
     *
     * @return 本次是否真的写入（便于调用方统计/测试）
     */
    boolean touch(long tenantId, long userId, String sessionId);

    /** 注销单个会话（用户自己退出登录，或管理员定向踢人）。 */
    void revoke(long tenantId, long userId, String sessionId);

    /**
     * 注销该用户的全部会话。
     *
     * @return 实际注销的会话数
     */
    int revokeAll(long tenantId, long userId);

    /**
     * 列出该用户的全部会话（按最后活跃时间倒序）。
     *
     * <p>实现需要清理"索引里还留着、但键已过期"的会话 ID ——
     * 这类悬空 ID 若不清掉会让在线列表越用越脏，且无法靠 TTL 自动消失。
     */
    List<SessionRecord> list(long tenantId, long userId);

    /**
     * 会话记录。
     *
     * @param sessionId    会话 ID（即访问令牌的 {@code jti}）
     * @param tokenVersion 签发该会话时的令牌版本号；与当前版本不一致即视为已吊销
     *                     （覆盖"会话注册表被清空后仍有旧令牌"的场景）
     * @param ip           登录 IP（<b>仅展示与审计用</b>，可被伪造）
     * @param userAgent    客户端标识，可能为空
     */
    record SessionRecord(
            String sessionId,
            long tenantId,
            long userId,
            String username,
            long tokenVersion,
            String ip,
            String userAgent,
            Instant loginTime,
            Instant lastActive) {

        /** 会话剩余的 TTL；用于把会话与刷新令牌的生命周期对齐。 */
        public OptionalLong remainingTtlSeconds(Instant now) {
            if (loginTime == null || lastActive == null) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Math.max(0, java.time.Duration.between(lastActive, now).toSeconds()));
        }
    }
}
