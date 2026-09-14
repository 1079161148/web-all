package com.webadmin.domain.iam.model.user;

import com.webadmin.domain.iam.event.UserEvent;
import com.webadmin.domain.iam.exception.IllegalUserStateException;
import com.webadmin.domain.shared.DomainEvent;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.domain.shared.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户聚合根。
 *
 * <h3>它保护哪些不变量</h3>
 * <ol>
 *   <li><b>登录失败锁定</b>：连续失败达阈值即锁定，锁定期内即使密码正确也拒绝。
 *       这条规则横跨"认证"与"用户状态"，散在 Service 里必然出现
 *       "某个登录入口忘了计数"的漏洞，因此内聚到聚合</li>
 *   <li><b>状态流转合法性</b>：交给 {@link UserStatus} 状态机裁决</li>
 *   <li><b>角色分配不产生重复</b>：用 {@code Set} 表达，而不是靠调用方去重</li>
 *   <li><b>用户名与密码非空且格式合法</b>：由值对象在构造时保证</li>
 * </ol>
 *
 * <h3>为什么 Clock 用构造器注入，而不是每个方法传参</h3>
 * 早期实现（{@code Tenant}）用的是方法传参，因为它的时间相关方法只有少数几个。
 * 而 User 几乎每个变更方法都要发事件、都要时间戳 —— 若逐个传参，
 * 会得到 8 个签名里都挂着 {@code Clock} 的方法，调用方还得在每个位置都想一遍"传哪个时钟"。
 * 构造器注入把这份噪声收敛到一处，同时保持完全可测试性
 * （测试里 {@code new User(..., Clock.fixed(...))} 即可）。
 *
 * <p><b>这不是"两种风格不一致"，而是按噪声量选择</b>：少量方法用传参更显式，
 * 大量方法用注入更清爽。风格统一是手段，不是目的。
 *
 * <h3>为什么 roleIds 在聚合内部</h3>
 * 「一个用户拥有哪些角色」直接决定其权限，属于用户语义的一部分；
 * {@code iam_user_role} 只是它的持久化形式。但聚合内只保存 <b>RoleId 引用</b>，
 * 绝不保存 Role 对象 —— 跨聚合引用对象会让聚合边界失效、事务范围失控（§20.1 坑位 4）。
 */
public class User {

    private final UserId id;
    private final TenantId tenantId;
    private final Username username;
    private final Clock clock;

    private PasswordHash password;
    private String nickname;
    private String email;
    private String phone;
    private Long deptId;
    private Integer sex;
    private String avatar;
    private UserStatus status;
    private String loginIp;
    private Instant loginTime;
    private int failCount;
    private Instant lockUntil;
    private final Set<RoleId> roleIds;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private User(UserId id, TenantId tenantId, Username username,
                 PasswordHash password, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.status = UserStatus.ACTIVE;
        this.sex = 2;
        this.failCount = 0;
        this.roleIds = new LinkedHashSet<>();
    }

    // ==================================================================
    // 创建与还原
    // ==================================================================

    /** 创建新用户（会产生领域事件）。 */
    public static User create(UserId id, TenantId tenantId, Username username,
                              String nickname, PasswordHash password,
                              Long deptId, Clock clock) {
        User user = new User(id, tenantId, username, password, clock);
        user.nickname = requireNickname(nickname);
        user.deptId = deptId;
        user.domainEvents.add(new UserEvent.Created(
                tenantId, id, username.value(), clock.instant()));
        return user;
    }

    /**
     * 从持久化还原（<b>不产生事件</b>）。
     *
     * <p>与 {@code Tenant.reconstitute} 同一道理：从库里读出来不是"发生了业务动作"。
     * 若这里发事件，任何一次查询都会往 Outbox 里塞垃圾记录。
     */
    public static User reconstitute(UserId id, TenantId tenantId, Username username,
                                    PasswordHash password, String nickname,
                                    String email, String phone, Long deptId,
                                    Integer sex, String avatar, UserStatus status,
                                    String loginIp, Instant loginTime, int failCount,
                                    Instant lockUntil, Set<RoleId> roleIds, Clock clock) {
        User user = new User(id, tenantId, username, password, clock);
        user.nickname = nickname;
        user.email = email;
        user.phone = phone;
        user.deptId = deptId;
        user.sex = sex == null ? 2 : sex;
        user.avatar = avatar;
        user.status = status == null ? UserStatus.ACTIVE : status;
        user.loginIp = loginIp;
        user.loginTime = loginTime;
        user.failCount = Math.max(failCount, 0);
        user.lockUntil = lockUntil;
        if (roleIds != null) {
            user.roleIds.addAll(roleIds);
        }
        return user;
    }

    // ==================================================================
    // 登录相关
    // ==================================================================

    /**
     * 断言当前用户允许登录。
     *
     * <p>放在聚合里而不是 Service 里，是为了让"能不能登录"只有一个判定入口。
     * 注意它同时检查<b>状态</b>与<b>锁定截止时间</b> —— 后者是关键：
     * {@code LOCKED} 状态可能在管理员未介入的情况下已经过期，
     * 若只看状态就会把一个本该放行的用户拒之门外。
     */
    public void assertCanLogin() {
        if (lockUntil != null && lockUntil.isAfter(clock.instant())) {
            long remainMinutes = Math.max(
                    1, Duration.between(clock.instant(), lockUntil).toMinutes());
            throw new IllegalUserStateException("账号已被锁定，请 " + remainMinutes + " 分钟后重试");
        }
        if (!status.canLogin()) {
            throw new IllegalUserStateException(switch (status) {
                case SUSPENDED -> "账号已被停用，请联系管理员";
                case LOCKED -> "账号已被锁定，请联系管理员解锁";
                case ACTIVE -> "账号当前状态不允许登录";
            });
        }
    }

    /** 登录成功：清零失败计数、解除锁定、记录登录信息。 */
    public void recordLoginSuccess(String ip) {
        if (this.status == UserStatus.LOCKED) {
            // 锁定期已过、密码正确 → 自动解锁。
            // 这里不要求管理员介入，因为「锁定」语义本就是"暂时拒绝"；
            // 把它变成需要人工解锁会让运维在高峰期被大量误锁账号淹没。
            changeStatus(UserStatus.ACTIVE, "锁定期满，登录成功自动解锁", false);
        }
        this.failCount = 0;
        this.lockUntil = null;
        this.loginIp = ip;
        this.loginTime = clock.instant();
        domainEvents.add(new UserEvent.LoggedIn(
                tenantId, id, username.value(), ip, clock.instant()));
    }

    /**
     * 登录失败：累加计数，达阈值则锁定。
     *
     * @return 剩余可尝试次数；{@code 0} 表示本次已触发锁定
     */
    public int recordLoginFailure(int maxFail, int lockMinutes) {
        int threshold = maxFail <= 0 ? 5 : maxFail;
        this.failCount++;
        if (this.failCount >= threshold) {
            this.status = UserStatus.LOCKED;
            this.lockUntil = clock.instant().plus(Duration.ofMinutes(Math.max(lockMinutes, 1)));
            domainEvents.add(new UserEvent.StatusChanged(
                    tenantId, id, username.value(),
                    UserStatus.ACTIVE.name(), UserStatus.LOCKED.name(),
                    "连续登录失败 " + failCount + " 次，自动锁定",
                    clock.instant()));
            return 0;
        }
        return threshold - this.failCount;
    }

    /** 管理员手动解锁。 */
    public void unlock() {
        if (this.status != UserStatus.LOCKED) {
            throw new IllegalUserStateException("用户当前不是锁定状态，无需解锁");
        }
        this.failCount = 0;
        changeStatus(UserStatus.ACTIVE, "管理员手动解锁", true);
    }

    // ==================================================================
    // 密码
    // ==================================================================

    /** 用户自助修改密码。 */
    public void changePassword(PasswordHash newPassword) {
        Objects.requireNonNull(newPassword, "newPassword");
        if (newPassword.equals(this.password)) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        this.password = newPassword;
        domainEvents.add(new UserEvent.PasswordChanged(
                tenantId, id, username.value(), false, clock.instant()));
    }

    /** 管理员重置密码（审计口径与自助修改不同，故事件中区分）。 */
    public void resetPassword(PasswordHash newPassword) {
        Objects.requireNonNull(newPassword, "newPassword");
        this.password = newPassword;
        // 重置密码同时解锁：用户被锁定的常见原因就是忘了密码
        this.failCount = 0;
        this.lockUntil = null;
        if (this.status == UserStatus.LOCKED) {
            this.status = UserStatus.ACTIVE;
        }
        domainEvents.add(new UserEvent.PasswordChanged(
                tenantId, id, username.value(), true, clock.instant()));
    }

    /**
     * 哈希算法升级：登录成功后用新参数重新加密。
     *
     * <p><b>刻意不发领域事件</b>：这是纯技术性变更，密码本身没变，
     * 不该触发"踢下线""发通知"等业务反应。
     */
    public void upgradePasswordHash(PasswordHash rehashed) {
        this.password = Objects.requireNonNull(rehashed, "rehashed");
    }

    // ==================================================================
    // 状态与资料
    // ==================================================================

    public void activate(String reason) {
        changeStatus(UserStatus.ACTIVE, reason, true);
    }

    public void suspend(String reason) {
        changeStatus(UserStatus.SUSPENDED, reason, true);
    }

    private void changeStatus(UserStatus target, String reason, boolean emitEvent) {
        if (!status.canTransitTo(target)) {
            throw new IllegalUserStateException("不允许从 " + status + " 流转到 " + target);
        }
        UserStatus previous = this.status;
        this.status = target;
        if (target == UserStatus.ACTIVE) {
            this.failCount = 0;
            this.lockUntil = null;
        }
        if (emitEvent) {
            domainEvents.add(new UserEvent.StatusChanged(
                    tenantId, id, username.value(),
                    previous.name(), target.name(), reason, clock.instant()));
        }
    }

    /** 更新基础资料。资料变更不影响权限，因此不发事件。 */
    public void updateProfile(String nickname, String email, String phone,
                              Long deptId, Integer sex, String avatar) {
        this.nickname = requireNickname(nickname);
        this.email = blankToNull(email);
        this.phone = blankToNull(phone);
        this.deptId = deptId;
        this.sex = sex == null ? 2 : sex;
        this.avatar = blankToNull(avatar);
    }

    /**
     * 重新分配角色（<b>全量覆盖</b>语义）。
     *
     * <p>用覆盖而非增删：前端权限分配界面的交互就是勾选树，提交的是最终状态。
     * 增量接口会引入"并发下 A 的勾选覆盖 B 的取消"这类冲突，
     * 而覆盖语义天然幂等、重放安全。
     */
    public void assignRoles(Set<RoleId> newRoleIds) {
        Set<RoleId> target = newRoleIds == null ? Set.of() : new LinkedHashSet<>(newRoleIds);
        if (target.equals(this.roleIds)) {
            return;
        }
        this.roleIds.clear();
        this.roleIds.addAll(target);
        domainEvents.add(new UserEvent.RolesChanged(
                tenantId, id, username.value(), this.roleIds.size(), clock.instant()));
    }

    public void markDeleted() {
        domainEvents.add(new UserEvent.Deleted(
                tenantId, id, username.value(), clock.instant()));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public boolean canLogin() {
        return status.canLogin();
    }

    public boolean isLocked() {
        return status == UserStatus.LOCKED;
    }

    public boolean isSuperAdmin() {
        return username.isSuperAdmin();
    }

    /** 取出并清空领域事件（取过一次就没了，避免重复发布）。 */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> snapshot = List.copyOf(domainEvents);
        domainEvents.clear();
        return snapshot;
    }

    private static String requireNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("用户昵称不能为空");
        }
        String trimmed = nickname.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("用户昵称长度不能超过 64");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---- 访问器 ----
    public UserId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public Username username() {
        return username;
    }

    public PasswordHash password() {
        return password;
    }

    public String nickname() {
        return nickname;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public Long deptId() {
        return deptId;
    }

    public Integer sex() {
        return sex;
    }

    public String avatar() {
        return avatar;
    }

    public UserStatus status() {
        return status;
    }

    public String loginIp() {
        return loginIp;
    }

    public Instant loginTime() {
        return loginTime;
    }

    public int failCount() {
        return failCount;
    }

    public Instant lockUntil() {
        return lockUntil;
    }

    public Set<RoleId> roleIds() {
        return Set.copyOf(roleIds);
    }
}
