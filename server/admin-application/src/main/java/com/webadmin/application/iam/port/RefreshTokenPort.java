package com.webadmin.application.iam.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 刷新令牌存储（Redis）。
 *
 * <h3>为什么刷新令牌是"不透明随机串"而不是 JWT</h3>
 * 访问令牌用 JWT 是因为要无状态校验（省一次查询）；而刷新令牌
 * <b>本来就必须查服务端</b>（要判断是否已被轮换/重用、要能撤销），
 * 既然躲不开查询，就没必要承担 JWT 的体积与"无法真正撤销"的代价。
 * 用 256 位随机串更短、更简单，也<b>不携带任何可被解读的信息</b>。
 *
 * <h3>为什么存哈希而不是原文</h3>
 * 与存密码同理：Redis 一旦泄漏（导出、快照、误开通访问），
 * 存原文等于把所有用户的会话直接送给攻击者；存哈希则拿到的只是"不可逆摘要"。
 * 代价是每次刷新多一次 SHA-256（微秒级）。
 *
 * <h3>为什么要"轮换 + 重放检测"</h3>
 * 只做轮换（每次刷新换一个新令牌、旧的作废）能限制单个令牌的可用窗口；
 * 但真正的价值在于<b>重放检测</b>：
 * <pre>
 *   攻击者窃取了刷新令牌 → 使用它 → 服务端轮换，旧令牌被标记为已用
 *   合法用户随后用同一个旧令牌刷新 → 命中"已用"标记
 *   ⇒ 说明出现了两个持有者 ⇒ 立刻吊销整个令牌族（family）并强制重新登录
 * </pre>
 * 也就是说：<b>重放检测把"令牌泄漏"从静默状态变成了一个可观测事件</b>，
 * 代价只是每族多维护一份已用标记。
 *
 * <h3>为什么按"族（family）"而不是按单个令牌吊销</h3>
 * 刷新令牌是链式轮换的：A → B → C。若只吊销当前令牌 C，
 * 攻击者可能正持有 B（合法用户没用到它就没被标记）—— 于是仍然可用。
 * 一族 = 一次登录会话产生的整条链，吊销族才是完整的。
 */
public interface RefreshTokenPort {

    /** 保存刷新令牌（登录或轮换时调用）。 */
    void save(String tokenHash, RefreshRecord record, Duration ttl);

    /** 按哈希查找令牌；<b>未知令牌一律视为无效</b>（不区分"不存在"与"伪造"）。 */
    Optional<RefreshRecord> find(String tokenHash);

    /**
     * 把令牌标记为"已被轮换使用过"。
     *
     * <p>不能直接删除：删掉之后重放会表现为"未知令牌"（也拒绝，但<b>无法区分
     * "攻击者重放"与"用户手抖重试"</b>），从而失去重放检测与告警能力。
     * 因此保留一个标记，TTL 与令牌族一致。
     */
    void markRotated(String tokenHash, Duration ttl);

    /** 该令牌是否已被轮换使用过（= 出现重放）。 */
    boolean isRotated(String tokenHash);

    /**
     * 吊销整个令牌族。
     *
     * @return 实际删除的令牌数（含已轮换的标记）
     */
    int revokeFamily(String familyId);

    /**
     * 吊销某用户的全部刷新令牌（改密码、停用、强制下线时调用）。
     *
     * @return 实际删除的令牌数
     */
    int revokeAllOfUser(long tenantId, long userId);

    /**
     * 吊销属于某会话的全部刷新令牌（注销单个会话时调用）。
     *
     * <p>为什么需要它：注销会话若只删会话键，刷新令牌仍能换出新访问令牌 ——
     * "退出登录"就形同虚设。而刷新令牌按"族"组织，
     * 没有反查索引就找不到该会话对应的族，因此实现必须在保存时
     * 维护一份「会话 → 族」的索引（与令牌同 TTL）。
     *
     * @return 实际吊销的令牌数；该会话没有刷新令牌时返回 0
     */
    int revokeBySession(long tenantId, long userId, String sessionId);

    /**
     * 刷新令牌记录（不含令牌原文）。
     *
     * @param familyId  令牌族标识（= 首次登录时生成的族 ID，轮换时保持不变）
     * @param sessionId 所属会话 ID（与访问令牌的 {@code jti} 相同），
     *                  用于"重放检测后连带把访问令牌的会话一起踢掉"
     */
    record RefreshRecord(String familyId, long tenantId, long userId, String sessionId) {
    }
}
