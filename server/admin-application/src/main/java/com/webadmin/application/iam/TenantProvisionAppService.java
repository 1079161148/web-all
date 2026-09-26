package com.webadmin.application.iam;

import com.webadmin.application.iam.command.CreateTenantCommand;
import com.webadmin.application.iam.command.CreateUserCommand;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.model.role.DataScope;
import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.model.role.RoleKey;
import com.webadmin.domain.iam.repository.RoleRepository;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户开通向导（一键开通）。
 *
 * <h3>它解决什么</h3>
 * 单独的"创建租户"接口只造出一个 PENDING 租户 —— 没有管理员、没有角色，
 * 任何人（包括平台管理员自己）都无法登录进去使用它。
 * "开通一个租户"在业务上的真实含义是<b>一串动作</b>：
 * <ol>
 *   <li>创建租户（PENDING）</li>
 *   <li>在租户下种一个「租户管理员」角色（SUPER_ADMIN 标识）</li>
 *   <li>创建管理员账号并绑定该角色</li>
 *   <li>激活租户（PENDING → ACTIVE）</li>
 * </ol>
 * 任何一步单独成功、整体失败，都会留下一个"看起来存在但没人进得去"的半成品。
 * 因此这里是一个<b>事务</b>：失败整体回滚，不留残骸。
 *
 * <h3>为什么管理员角色复用 SUPER_ADMIN 标识</h3>
 * 与平台管理员同一机制：权限码判定直接通过、数据范围视为 ALL ——
 * 但数据行被租户拦截器<b>圈在本租户内</b>。
 * "租户内的超管"正是多租户系统的标准语义：租户管理员管理自己的一切，
 * 永远碰不到别的租户。若给租户管理员单独造一套角色/菜单授权，
 * 反而要回答"新菜单要不要同步给所有租户的管理员"这种永远答不好的问题。
 *
 * <h3>初始密码只返回一次</h3>
 * 与所有凭证一致：响应里带一次明文，由操作者转交租户；
 * 之后库里只有哈希，丢了只能重置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisionAppService {

    private final TenantAppService tenantAppService;
    private final RoleRepository roleRepository;
    private final UserAppService userAppService;
    private final com.webadmin.domain.iam.repository.TenantRepository tenantRepository;
    private final com.webadmin.domain.shared.IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 向导未显式指定密码时的生成器。
     *
     * <h3>为什么不用"平台初始密码"作为默认值</h3>
     * 平台初始密码（如 {@code Admin@123456}）对账号 {@code admin} 来说
     * <b>必然违反密码策略</b>（策略禁止密码包含用户名）——
     * 也就是说向导的"默认组合"是一个保证失败的组合，而且失败表现为
     * 策略抛出的异常。既然初始密码本来就要展示给操作者转交，
     * 不如每次生成一个强随机串：既绕开策略冲突，也更安全
     * （固定初始密码意味着所有新租户的起点密码相同）。
     */
    private String generateInitialPassword() {
        final String lower = "abcdefghijkmnpqrstuvwxyz";
        final String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final String digits = "23456789";
        final String special = "!@#$%^&*";
        String alphabet = lower + upper + digits + special;
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(14);
        // 每类至少一个，其余从全集随机 —— 保证满足复杂度策略
        sb.append(lower.charAt(random.nextInt(lower.length())));
        sb.append(upper.charAt(random.nextInt(upper.length())));
        sb.append(digits.charAt(random.nextInt(digits.length())));
        sb.append(special.charAt(random.nextInt(special.length())));
        for (int i = 4; i < 14; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /**
     * 开通命令。
     *
     * @param adminUsername 租户管理员的登录账号（租户内唯一，跨租户可重名，如各租户都用 admin）
     * @param adminPassword 初始密码；为空时使用平台配置的初始密码
     */
    public record ProvisionCommand(String code, String name, String planCode,
                                   String adminUsername, String adminPassword) {
    }

    /**
     * 开通结果。
     *
     * @param initialPassword 初始密码明文。<b>只出现这一次</b>，之后无法再取回
     */
    public record ProvisionResult(long tenantId, String tenantCode, String planCode,
                                  String adminUsername, String initialPassword) {
    }

    /**
     * 一键开通：建租户 → 激活 → 种管理员角色 → 建管理员。
     *
     * <p>⚠️ 激活必须在建管理员<b>之前</b>（第一次就放错了）：创建用户要过
     * 配额拦截，而 {@code consumeQuota} 只允许 ACTIVE 租户 ——
     * PENDING 租户建不了人。激活提前后，中间状态仍然不可见
     * （整个方法是一个事务，失败整体回滚），外部只会看到"开通成功"或"什么都没有"。
     *
     * <p>管理员账号的创建会走 {@link UserAppService#createUser}，
     * 因此<b>自然计入租户的 USER 配额</b>（管理员也是用户，配额语义保持一致）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProvisionResult provision(ProvisionCommand command) {
        // ① 创建租户（PENDING）：复用既有服务，唯一性校验与事件发布都在里面
        TenantId tenantId = tenantAppService.createTenant(new CreateTenantCommand(
                command.code(), command.name(), command.planCode(), null));
        long tid = tenantId.value();

        // ② 立即激活：后续建管理员要过配额/状态拦截（ACTIVE 才放行）
        tenantAppService.activate(tenantId);

        // 密码只算一次：落库的与返回给操作者的必须是同一个值
        String initialPassword = command.adminPassword() == null || command.adminPassword().isBlank()
                ? generateInitialPassword()
                : command.adminPassword();

        // ② 建管理员角色与账号：需要以该租户的身份执行（用户表按上下文落租户）。
        //    用完即恢复 —— 平台管理员自己的上下文不能被改掉
        Long previous = TenantContext.get().orElse(null);
        TenantContext.set(tid);
        try {
            Role adminRole = Role.create(
                    idGenerator.nextRoleId(),
                    TenantId.ofPersisted(tid),
                    RoleKey.of("SUPER_ADMIN"),
                    "租户管理员",
                    1,
                    DataScope.ALL,
                    clock);
            roleRepository.save(adminRole);

            userAppService.createUser(new CreateUserCommand(
                    command.adminUsername(),
                    "租户管理员",
                    initialPassword,
                    null,
                    null,
                    null,
                    2,
                    Set.of(adminRole.id().value())));
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }

        log.info("租户开通完成: id={}, code={}, 管理员={}", tid, command.code(), command.adminUsername());
        return new ProvisionResult(tid, command.code(), command.planCode(),
                command.adminUsername(), initialPassword);
    }
}
