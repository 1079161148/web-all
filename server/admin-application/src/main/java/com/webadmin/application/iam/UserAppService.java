package com.webadmin.application.iam;

import com.webadmin.application.iam.command.CreateUserCommand;
import com.webadmin.application.iam.command.UpdateUserCommand;
import com.webadmin.application.iam.security.PermissionResolver;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.user.PasswordHash;
import com.webadmin.domain.iam.model.user.PasswordPolicy;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.UserStatus;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.iam.port.PasswordEncoderPort;
import com.webadmin.domain.iam.repository.UserRepository;
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

    /**
     * 新建用户的默认密码。
     *
     * <p>⚠️ 当前来自配置文件。设计文档 §9.4 把这类"平台参数"放在 {@code plt_config}
     * 里以支持租户级覆盖（{@code sys.user.init-password}）。
     * 走配置中心是 P2 的工作；现在先用配置项占位，但<b>接口形态已经按"可被覆盖"设计</b>，
     * 届时只需换数据来源，调用点不动。
     */
    @Value("${webadmin.user.default-password:Admin@123456}")
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

        String rawPassword = command.rawPassword() == null || command.rawPassword().isBlank()
                ? defaultPassword
                : command.rawPassword();
        PasswordPolicy.validate(rawPassword, passwordMinLength, username.value());

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

        user.markDeleted();
        userRepository.save(user);
        // 删除后必须清缓存，否则"已删除的人"在缓存有效期内仍有权限
        permissionResolver.refresh(TenantContext.require(), userId);
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
        PasswordPolicy.validate(effective, passwordMinLength, user.username().value());

        user.resetPassword(passwordEncoder.encode(effective));
        userRepository.save(user);
        // 重置密码 = 凭证失效 → 必须让该用户的在线令牌立即失效。
        // 当前实现通过清权限缓存来达到"后续请求重新鉴权"的效果；
        // 真正的"令牌吊销"需要令牌版本号或黑名单（P2，见设计文档 §八）。
        permissionResolver.refresh(TenantContext.require(), userId);
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
        permissionResolver.refresh(TenantContext.require(), userId);
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
    // 内部
    // ==================================================================

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
