package com.webadmin.infrastructure.security;

import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.model.role.RoleKey;
import com.webadmin.domain.iam.model.user.PasswordHash;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.iam.port.PasswordEncoderPort;
import com.webadmin.domain.iam.repository.RoleRepository;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.IdGenerator;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 超级管理员初始化器（幂等）。
 *
 * <h3>为什么管理员账号不在 Flyway 脚本里插</h3>
 * 因为它需要一个 <b>BCrypt 哈希</b>，而哈希是运行时才算出来的。
 * 若在 SQL 里写死一个哈希字符串，后果是：
 * <ul>
 *   <li>"密码到底是什么"只能靠注释说明，注释过期后没人敢动这个账号</li>
 *   <li>换环境时无法通过配置指定初始密码，只能改库</li>
 *   <li>代码评审时哈希是"不可验证的密文"，没人能确认它对应哪个密码</li>
 * </ul>
 * 改为启动时用真实算法计算，初始密码来自配置，首次创建时打印到日志。
 *
 * <h3>幂等性</h3>
 * 每次启动都会执行，但只有 admin 不存在时才创建。
 * 因此重复重启不会重置密码 —— <b>这一点必须保证</b>，
 * 否则一次误重启就会把生产超管密码改回默认值。
 *
 * <h3>为什么必须以平台租户身份执行</h3>
 * 所有查询与写入都会被租户拦截器加上 {@code tenant_id} 条件。
 * 若不在上下文中显式设置租户，{@code TenantContext.require()} 会直接抛异常 ——
 * 这是设计使然（防漏隔离），初始化器必须遵守同样的规则，不能给自己开后门。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoderPort passwordEncoder;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final org.springframework.core.env.Environment environment;

    @Value("${webadmin.security.init-admin.enabled:true}")
    private boolean enabled;

    /**
     * 是否在启动时<b>重置</b>已存在超管账号的密码。
     *
     * <h3>为什么需要这个开关</h3>
     * 初始密码只在账号<b>首次创建时</b>打印到启动日志一次。一旦错过（日志滚掉了、
     * 换人接手、容器重建后日志丢失），就<b>没有任何办法找回</b> ——
     * 数据库里只有 BCrypt 哈希，SQL 也算不出原密码。结果是整个系统进不去，
     * 只能靠改代码或删库重建。<b>这是个真实的运维死角，不是一个"注意别忘"的问题。</b>
     *
     * <h3>为什么默认关闭，并且要挡住生产环境</h3>
     * 一个"能把超管密码重置为配置值"的开关，如果默认开启或能在生产生效，
     * 就是一个后门：任何能改环境变量的人都能接管系统。
     * 因此两道防护：
     * <ol>
     *   <li>默认 {@code false}</li>
     *   <li>{@code prod} profile 下<b>即使显式打开也拒绝执行</b>，并记录 error 日志。
     *       生产环境的正确恢复路径是走数据库变更流程 + 人工授权，不是启动参数</li>
     * </ol>
     */
    @Value("${webadmin.security.init-admin.reset-password:false}")
    private boolean resetPassword;

    @Value("${webadmin.security.init-admin.tenant-id:1}")
    private long tenantId;

    @Value("${webadmin.security.init-admin.username:admin}")
    private String adminUsername;

    @Value("${webadmin.security.init-admin.password:Admin@123456}")
    private String initPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("超管初始化已禁用（webadmin.security.init-admin.enabled=false）");
            return;
        }

        // 建立租户上下文：初始化器不是 HTTP 请求，没有请求头可依赖。
        // 必须 try/finally 清理 —— 虽然这里是启动期的 main 线程，
        // 但养成"设置上下文必配清理"的习惯比"这次用不到就不写"更可靠。
        TenantContext.set(tenantId);
        try {
            initialize();
        } finally {
            TenantContext.clear();
        }
    }

    private void initialize() {
        Username username = Username.of(adminUsername);

        if (userRepository.existsByUsername(username)) {
            if (resetPassword) {
                resetExistingAdmin(username);
            } else {
                log.info("超管账号已存在，跳过初始化（username={} tenantId={}）", username.value(), tenantId);
            }
            return;
        }

        Role superAdminRole = roleRepository.findByRoleKey(RoleKey.of(RoleKey.SUPER_ADMIN))
                .orElseThrow(() -> new IllegalStateException(
                        "未找到内置超管角色 " + RoleKey.SUPER_ADMIN
                                + "。请确认 Flyway 迁移 V1.0.4 已执行（种子数据包含该角色）"));

        PasswordHash passwordHash = passwordEncoder.encode(initPassword);

        User admin = User.create(
                idGenerator.nextUserId(),
                TenantId.ofPersisted(tenantId),
                username,
                "超级管理员",
                passwordHash,
                null,
                clock);
        admin.assignRoles(Set.of(superAdminRole.id()));

        userRepository.save(admin);

        // 打印初始密码：这是必要的运维信息（否则没人能登录），
        // 但必须明确要求修改 —— 并且这条日志只在首次创建时出现一次。
        log.warn("================================================================");
        log.warn("已创建超级管理员账号，请立即登录并修改密码");
        log.warn("  租户 ID : {}", tenantId);
        log.warn("  用户名  : {}", username.value());
        log.warn("  初始密码: {}", initPassword);
        log.warn("  ⚠️ 该密码仅在本次日志中出现一次，请立刻修改");
        log.warn("  ⚠️ 生产环境请通过 webadmin.security.init-admin.password 覆盖默认值");
        log.warn("  💡 若错过这条日志导致无法登录，可临时用"
                + " webadmin.security.init-admin.reset-password=true 重置（prod 下会被拒绝）");
        log.warn("================================================================");
    }

    /**
     * 重置已存在超管账号的密码。
     *
     * <p>走的是聚合的 {@code resetPassword} 而不是直接 UPDATE 一行 ——
     * 这样"重置密码同时解除锁定、清零失败计数"这些不变量会一并生效
     * （见 {@code User#resetPassword}）。直接改库会绕过它们，
     * 结果是密码改对了但账号仍是 LOCKED 状态，用户依然登不进去 ——
     * 一个会让人怀疑"密码是不是没改成功"的陷阱。
     */
    private void resetExistingAdmin(Username username) {
        if (isProductionProfile()) {
            log.error("已拒绝在 prod 环境下重置超管密码（webadmin.security.init-admin.reset-password=true）。"
                    + "生产环境的密码恢复必须走变更流程，不允许通过启动参数完成。");
            return;
        }

        User admin = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("超管账号查询结果不一致，请检查数据库"));

        admin.resetPassword(passwordEncoder.encode(initPassword));

        // 顺手确保超管角色在位：能进到这个分支说明账号存在，
        // 但角色关联可能因人工操作或数据迁移而丢失，那会表现为
        // "登录成功但什么都看不到"，也是一种排查成本很高的故障
        Role superAdminRole = roleRepository.findByRoleKey(RoleKey.of(RoleKey.SUPER_ADMIN))
                .orElseThrow(() -> new IllegalStateException(
                        "未找到内置超管角色 " + RoleKey.SUPER_ADMIN));
        admin.assignRoles(Set.of(superAdminRole.id()));

        userRepository.save(admin);

        log.warn("================================================================");
        log.warn("⚠️ 已按配置重置超管密码（webadmin.security.init-admin.reset-password=true）");
        log.warn("  用户名: {}", username.value());
        log.warn("  新密码: {}", initPassword);
        log.warn("  ⚠️ 请立即登录并修改密码，然后把该开关关掉");
        log.warn("================================================================");
    }

    /** 是否处于生产环境（任一 active profile 为 prod / production）。 */
    private boolean isProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile.toLowerCase();
            if ("prod".equals(normalized) || "production".equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
