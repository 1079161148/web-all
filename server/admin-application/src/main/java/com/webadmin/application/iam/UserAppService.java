package com.webadmin.application.iam;

import com.webadmin.application.iam.command.CreateUserCommand;
import com.webadmin.application.iam.command.UpdateUserCommand;
import com.webadmin.application.iam.port.RefreshTokenPort;
import com.webadmin.application.iam.port.SessionRegistryPort;
import com.webadmin.application.iam.port.TokenVersionPort;
import com.webadmin.application.iam.security.PermissionResolver;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.tenant.QuotaType;
import com.webadmin.domain.iam.model.tenant.Tenant;
import com.webadmin.domain.iam.model.user.PasswordHash;
import com.webadmin.domain.iam.model.user.PasswordPolicy;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.UserStatus;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.iam.port.PasswordEncoderPort;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.iam.repository.TenantRepository;
import com.webadmin.domain.shared.IdGenerator;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.domain.shared.UserId;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户应用服务（写侧）。
 *
 * <h3>三类操作的共同收尾：刷新权限缓存</h3>
 * 角色分配、状态变更、密码重置都会影响"这个人当前能做什么"。
 * 若只落库而不清缓存，最长要等 30 分钟（权限缓存 TTL）才生效 ——
 * 而运维在界面上看到的是"操作成功"，于是会以为功能坏了。
 *
 * <p>所以每个改变权限语义的方法末尾都必须
 * {@code permissionResolver.refresh(...)}。这是<b>容易漏且不会报错</b>的一步，
 * 因此统一放在一个私有方法里，并在每个入口显式调用 —— 让它在代码里可被审阅。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAppService {

    private final UserRepository userRepository;
    private final PermissionResolver permissionResolver;
    private final CurrentUserPort currentUserPort;
    private final PasswordEncoderPort passwordEncoder;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SessionRegistryPort sessionRegistry;
    private final RefreshTokenPort refreshTokenStore;
    private final TokenVersionPort tokenVersionPort;
    private final TenantRepository tenantRepository;

    /**
     * 新建用户的默认密码。
     *
     * <p>⚠️ 默认值曾经是 {@code Admin@123456} —— 而密码策略禁止弱片段
     * （"admin"、"123456" 都在清单里），也就是说这个默认值<b>必然无法通过
     * 自己系统的策略</b>：任何"新增用户留空密码"的操作都会失败
     * （此前因策略异常未被翻译，表现为 500，更具迷惑性）。
     * 现改为满足策略的取值；仍可通过配置覆盖。
     *
     * <p>⚠️ 当前来自配置文件。设计文档 §9.4 把这类"平台参数"放在 {@code plt_config}
     * 里以支持租户级覆盖（{@code sys.user.init-password}）。
     * 走配置中心是 P2 的工作；现在先用配置项占位，但<b>接口形态已经按"可被覆盖"设计</b>，
     * 届时只需换数据来源，调用点不动。
     */
    @Value("${webadmin.user.default-password:Init#2026!Xq}")
    private String defaultPassword;

    /** 密码最小长度（同样应来自 plt_config）。 */
    @Value("${webadmin.user.password-min-length:8}")
    private int passwordMinLength;

    // ==================================================================
    // 新增
    // ==================================================================

    @Transactional
    public Long createUser(CreateUserCommand command) {
        long tenantId = TenantContext.require();
        Username username = Username.of(command.username());

        // 唯一性预检。注意这只是"提前给出友好提示"，
        // 真正的唯一性由数据库唯一索引 (tenant_id, username, del_flag) 保证 ——
        // 并发下两个请求可能同时通过预检，此时后者会被数据库拒绝。
        // 不能因为"查过了"就认为安全。
        if (userRepository.existsByUsername(username)) {
            throw new BizException(IamErrorCode.USERNAME_DUPLICATED,
                    "账号「" + username.value() + "」在该租户下已存在");
        }

        // ---- 配额与租户状态拦截（配额在执行点生效，而不是停留在配置里）----
        // 与用户创建同一事务：扣减配额后若创建失败，扣减一并回滚，不会"白扣"
        Tenant tenant = loadTenantForQuota(tenantId);
        tenant.consumeQuota(QuotaType.USER, 1);
        tenantRepository.save(tenant);

        String rawPassword = command.rawPassword() == null || command.rawPassword().isBlank()
                ? defaultPassword
                : command.rawPassword();
        applyPasswordPolicy(rawPassword, username.value());

        User user = User.create(
                idGenerator.nextUserId(),
                TenantId.ofPersisted(tenantId),
                username,
                command.nickname(),
                passwordEncoder.encode(rawPassword),
                command.deptId(),
                clock);
        user.updateProfile(command.nickname(), command.email(), command.phone(),
                command.deptId(), command.sex(), null);
        user.assignRoles(toRoleIds(command.roleIds()));

        userRepository.save(user);
        log.info("创建用户 tenantId={} userId={} username={} 角色数={}",
                tenantId, user.id().value(), username.value(), user.roleIds().size());
        return user.id().value();
    }

    // ==================================================================
    // 修改
    // ==================================================================

    @Transactional
    public void updateUser(long userId, UpdateUserCommand command) {
        User user = loadUser(userId);
        user.updateProfile(command.nickname(), command.email(), command.phone(),
                command.deptId(), command.sex(), command.avatar());

        Set<RoleId> before = user.roleIds();
        user.assignRoles(toRoleIds(command.roleIds()));
        boolean rolesChanged = !before.equals(user.roleIds());

        userRepository.save(user);
        refreshPermissionsIfNeeded(rolesChanged, userId);
    }

    // ==================================================================
    // 删除
    // ==================================================================

    @Transactional
    public void deleteUser(long userId) {
        User user = loadUser(userId);
        assertNotSelf(user);
        assertNotSuperAdmin(user, "删除");

        // 删除 = 该用户的一切访问终止 → 提升版本号。
        // 此后 TokenVersionPort 对它返回 -1（用户不存在），令牌一律被拒
        user.bumpTokenVersion();
        user.markDeleted();
        userRepository.save(user);
        long tenantId = TenantContext.require();
        // 归还用户名额：只有扣减没有归还的配额是单向消耗，
        // 表现为"删了人却还是建不了新用户"—— 用户会把它当成 bug（它确实是）
        tenantRepository.findById(TenantId.ofPersisted(tenantId)).ifPresent(tenant -> {
            tenant.releaseQuota(QuotaType.USER, 1);
            tenantRepository.save(tenant);
        });
        revokeSessionStateAfterCommit(tenantId, userId);
        // 删除后必须清缓存，否则"已删除的人"在缓存有效期内仍有权限
        permissionResolver.refresh(tenantId, userId);
        log.info("删除用户 userId={} username={}", userId, user.username().value());
    }

    // ==================================================================
    // 密码
    // ==================================================================

    @Transactional
    public void resetPassword(long userId, String rawPassword) {
        User user = loadUser(userId);
        String effective = rawPassword == null || rawPassword.isBlank()
                ? defaultPassword : rawPassword;
        applyPasswordPolicy(effective, user.username().value());

        user.resetPassword(passwordEncoder.encode(effective));
        // 重置密码 = 旧凭证可能已被他人持有 → 提升令牌版本号：
        // 该用户已签发的全部访问令牌立即失效，且持久生效（不依赖 Redis 是否存活）。
        // 这取代了原先"只清权限缓存"的近似做法（那会让旧令牌活满整个有效期）
        user.bumpTokenVersion();
        userRepository.save(user);
        long tenantId = TenantContext.require();
        revokeSessionStateAfterCommit(tenantId, userId);
        permissionResolver.refresh(tenantId, userId);
        log.info("重置用户密码 userId={} username={}", userId, user.username().value());
    }

    // ==================================================================
    // 状态
    // ==================================================================

    @Transactional
    public void changeStatus(long userId, String targetStatus, String reason) {
        User user = loadUser(userId);
        UserStatus target = parseStatus(targetStatus);

        if (target == UserStatus.SUSPENDED) {
            assertNotSelf(user);
            assertNotSuperAdmin(user, "停用");
            user.suspend(reason);
            // 停用 = 立即收回访问能力 → 提升令牌版本号。
            // 激活分支刻意不提升：启用一个"令牌本就已全部失效"的账号没有意义
            user.bumpTokenVersion();
        } else if (target == UserStatus.ACTIVE) {
            user.activate(reason);
        } else {
            // LOCKED 由登录失败自动触发，不提供手工入口 ——
            // 否则管理员可以"手动锁人"，而这个能力与锁定机制的语义（防暴力破解）无关，
            // 却会带来"为什么他被锁了"的排查成本。真要限制某人，应该停用他。
            throw new BizException(IamErrorCode.USER_ILLEGAL_STATE,
                    "不支持手工将用户置为锁定状态；如需限制访问请使用「停用」");
        }

        userRepository.save(user);
        long tenantId = TenantContext.require();
        if (target == UserStatus.SUSPENDED) {
            revokeSessionStateAfterCommit(tenantId, userId);
        }
        permissionResolver.refresh(tenantId, userId);
        log.info("变更用户状态 userId={} → {} 原因={}", userId, target, reason);
    }

    @Transactional
    public void unlock(long userId) {
        User user = loadUser(userId);
        user.unlock();
        userRepository.save(user);
        permissionResolver.refresh(TenantContext.require(), userId);
        log.info("解锁用户 userId={} username={}", userId, user.username().value());
    }

    // ==================================================================
    // 角色分配
    // ==================================================================

    @Transactional
    public void assignRoles(long userId, Set<Long> roleIds) {
        User user = loadUser(userId);
        user.assignRoles(toRoleIds(roleIds));
        userRepository.save(user);
        permissionResolver.refresh(TenantContext.require(), userId);
        log.info("分配用户角色 userId={} 角色数={}", userId, roleIds == null ? 0 : roleIds.size());
    }

    // ==================================================================
    // 强制下线
    // ==================================================================

    /**
     * 管理员强制用户下线：提升令牌版本号 + 清会话与刷新令牌。
     *
     * <h3>它与「停用」的区别</h3>
     * 停用是<b>状态变更</b>（账号不能再登录，需要再启用）；
     * 强制下线只终止<b>当前在线状态</b>（账号本身正常，重新登录即可继续用）。
     * 典型场景：怀疑令牌泄露、发现异常登录地、需要立即阻断但不想走停用流程。
     */
    @Transactional
    public void forceLogout(long userId) {
        User user = loadUser(userId);
        long tenantId = TenantContext.require();
        user.bumpTokenVersion();
        userRepository.save(user);
        revokeSessionStateAfterCommit(tenantId, userId);
        permissionResolver.refresh(tenantId, userId);
        log.info("管理员强制用户下线 userId={} username={}", userId, user.username().value());
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 在<b>事务提交后</b>执行"会话吊销三件套"：
     * 清令牌版本号缓存、注销全部会话、吊销全部刷新令牌。
     *
     * <h3>为什么必须等提交</h3>
     * 最关键的是版本号缓存的失效：若在提交前清缓存，
     * 并发请求会立刻回源 —— 读到的还是<b>尚未提交的旧版本号</b>并写回缓存，
     * 于是吊销被"抵消"，旧令牌在缓存 TTL 内（最长 10 分钟）继续可用。
     * 提交后执行就没有这个窗口。
     *
     * <p>会话与刷新令牌的清理放进同一个钩子还有一层含义：
     * 若事务最终回滚（比如后面某处校验失败），用户的密码/状态其实没变，
     * 此时<b>不该</b>把他踢下线 —— 提交后才吊销正好保证了这一点。
     *
     * <p>无事务上下文时直接执行（如测试或未来的非事务调用方）。
     */
    private void revokeSessionStateAfterCommit(long tenantId, long userId) {
        Runnable revocation = () -> {
            tokenVersionPort.evict(tenantId, userId);
            sessionRegistry.revokeAll(tenantId, userId);
            refreshTokenStore.revokeAllOfUser(tenantId, userId);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revocation.run();
                }
            });
        } else {
            revocation.run();
        }
    }

    /**
     * 加载当前租户聚合（配额拦截与状态校验的入口）。
     *
     * <p>{@code consumeQuota} 内部会拒绝 SUSPENDED / 终态租户，
     * {@code assertAccessible} 会拒绝已过期租户 ——
     * "套餐用完/租户过期就建不了用户"由此成为结构性保证，
     * 而不是某个接口里的一段 if。
     */
    private Tenant loadTenantForQuota(long tenantId) {
        Tenant tenant = tenantRepository.findById(TenantId.ofPersisted(tenantId))
                .orElseThrow(() -> new BizException(IamErrorCode.TENANT_NOT_FOUND,
                        "租户不存在（ID=" + tenantId + "）"));
        tenant.assertAccessible(clock);
        return tenant;
    }

    /**
     * 密码策略校验的统一入口：把策略抛出的 {@code IllegalArgumentException}
     * 翻译成业务异常。
     *
     * <p>不翻译的后果是<b>可预期的拒绝变成 500 系统异常</b>：
     * "密码不能包含用户名"是调用方能理解并纠正的问题，
     * 以"系统异常"的形式出现会让使用者以为是自己搞坏了系统。
     * 策略类保持在领域层（不依赖应用层的异常体系），
     * 翻译发生在应用层 —— 这是两层的正确分工。
     */
    private void applyPasswordPolicy(String rawPassword, String username) {
        try {
            PasswordPolicy.validate(rawPassword, passwordMinLength, username);
        } catch (IllegalArgumentException ex) {
            throw new BizException(IamErrorCode.PASSWORD_TOO_WEAK, ex.getMessage());
        }
    }

    private User loadUser(long userId) {
        return userRepository.findById(UserId.of(userId))
                .orElseThrow(() -> new BizException(IamErrorCode.USER_NOT_FOUND,
                        "用户不存在或已被删除（ID=" + userId + "）"));
    }

    private Set<RoleId> toRoleIds(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<RoleId> result = new LinkedHashSet<>();
        for (Long id : roleIds) {
            if (id != null) {
                result.add(RoleId.of(id));
            }
        }
        return result;
    }

    private UserStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new BizException(IamErrorCode.USER_ILLEGAL_STATE, "状态不能为空");
        }
        try {
            return UserStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException(IamErrorCode.USER_ILLEGAL_STATE,
                    "无法识别的用户状态：" + status);
        }
    }

    /** 禁止把自己删掉/停用 —— 否则一次误操作就能把自己锁在系统外，且无法自救。 */
    private void assertNotSelf(User target) {
        Long currentUserId = currentUserPort.currentUser()
                .map(com.webadmin.application.security.CurrentUser::userId)
                .orElse(null);
        if (currentUserId != null && currentUserId == target.id().value()) {
            throw new BizException(IamErrorCode.USER_ILLEGAL_STATE,
                    "不能对当前登录账号执行该操作");
        }
    }

    /**
     * 禁止删除/停用超管账号。
     *
     * <p>这条与 {@code Role} 的内置角色保护是<b>两个不同层面</b>的防护：
     * 角色保护防的是"删掉 SUPER_ADMIN 角色"，这里防的是"删掉唯一持有它的账号"。
     * 两者任一失守，系统都会进入"没有人拥有完整权限"的死锁状态，只能改库恢复。
     */
    private void assertNotSuperAdmin(User target, String action) {
        if (target.isSuperAdmin()) {
            throw new BizException(IamErrorCode.USER_ILLEGAL_STATE,
                    "内置超管账号不允许" + action);
        }
    }

    private void refreshPermissionsIfNeeded(boolean changed, long userId) {
        // 只有权限语义真的变了才刷新。资料变更（昵称、手机号）不该触发缓存重建 ——
        // 那会让"改个昵称"这种高频操作也去打一遍数据库。
        if (changed) {
            permissionResolver.refresh(TenantContext.require(), userId);
        }
    }
}
