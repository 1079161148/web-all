package com.webadmin.domain.iam.model.user;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 用户状态与状态机。
 *
 * <h3>为什么把状态机放进枚举而不是散在 Service 里</h3>
 * 状态流转规则属于<b>领域知识</b>，不是编排逻辑。放在枚举里之后：
 * <ul>
 *   <li>规则只有一处，不可能出现"这个入口校验了、那个入口忘了"</li>
 *   <li>可以被单元测试穷举覆盖（所有边都测一遍，成本极低）</li>
 *   <li>读代码时不需要跳三个文件才能确认某个流转是否合法</li>
 * </ul>
 *
 * <h3>与租户状态的差异</h3>
 * 用户多了一个 {@code LOCKED}，但它是**可自动恢复**的（等锁定期满或管理员解锁），
 * 而 {@code SUSPENDED} 必须人工恢复。区分二者很重要：
 * 登录失败锁定不该把账号变成"需要管理员介入"的状态。
 */
public enum UserStatus {

    /** 正常可用。 */
    ACTIVE,

    /** 已停用：管理员主动停用，必须人工恢复。 */
    SUSPENDED,

    /** 已锁定：登录失败次数超阈值触发，到期自动解锁或管理员解锁。 */
    LOCKED,
    ;

    private static final Map<UserStatus, Set<UserStatus>> ALLOWED_TRANSITIONS = Map.of(
            ACTIVE, EnumSet.of(SUSPENDED, LOCKED),
            SUSPENDED, EnumSet.of(ACTIVE),
            LOCKED, EnumSet.of(ACTIVE, SUSPENDED)
    );

    /** 是否允许流转到目标状态。 */
    public boolean canTransitTo(UserStatus target) {
        if (this == target) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 该状态下用户能否登录。 */
    public boolean canLogin() {
        return this == ACTIVE;
    }
}
