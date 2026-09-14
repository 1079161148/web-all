package com.webadmin.application.iam;

import com.webadmin.application.iam.command.LoginCommand;
import com.webadmin.application.iam.dto.CurrentUserDTO;
import com.webadmin.application.iam.dto.LoginResult;
import com.webadmin.application.iam.dto.MenuDTO;
import com.webadmin.application.iam.port.PermissionCachePort.CachedPermissions;
import com.webadmin.application.iam.port.TokenIssuerPort;
import com.webadmin.application.iam.security.PermissionResolver;
import com.webadmin.application.security.CurrentUser;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.menu.Menu;
import com.webadmin.domain.iam.model.user.PasswordHash;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.iam.port.PasswordEncoderPort;
import com.webadmin.domain.iam.repository.MenuRepository;
import com.webadmin.domain.iam.repository.RoleRepository;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.MenuId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证与当前用户应用服务。
 *
 * <h3>登录流程的关键设计</h3>
 * <ol>
 *   <li><b>用户不存在与密码错误返回同一个错误</b>（{@code BAD_CREDENTIALS}）。
 *       若区分开来，攻击者就能用登录接口枚举出系统里有哪些用户名 ——
 *       这是最基础也最常被忽略的一条安全要求</li>
 *   <li><b>用户不存在时也走一次假校验</b>：见 {@link #dummyVerify} 注释，
 *       目的消除基于响应耗时的用户名枚举侧信道</li>
 *   <li><b>失败计数在用户存在时才累加</b>：对不存在的用户计数会被用于
 *       制造大量无用 Redis/DB 写，反而成为 DoS 面</li>
 *   <li><b>锁定后不再校验密码</b>：锁定期间连正确密码也拒绝，
 *       否则攻击者可以用锁定状态反推密码是否正确</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthAppService {

    /**
     * 连续失败上限与锁定时长的兜底默认值（真实值来自 {@code plt_config}，P2 接入配置后改为可配）。
     *
     * <p>声明为 {@code public} 是为了让测试直接引用同一份定义，而不是在自己的代码里
     * 再抄一个 {@code 5}。<b>测试里硬编码业务阈值是"测试与实现各写一份"的典型隐患</b>：
     * 阈值从 5 改成 3 之后，测试仍然"通过"（因为它也错了），却不再验证真实行为。
     */
    public static final int DEFAULT_MAX_FAIL = 5;
    public static final int DEFAULT_LOCK_MINUTES = 30;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;
    private final PasswordEncoderPort passwordEncoder;
    private final PermissionResolver permissionResolver;
    private final TokenIssuerPort tokenIssuer;
    private final CurrentUserPort currentUserPort;

    // ==================================================================
    // 登录
    // ==================================================================

    /**
     * 登录。
     *
     * <h3>⚠️ {@code noRollbackFor = BizException.class} 是必需的，不是可选优化</h3>
     * 本方法在其间会做一件<b>必须持久化</b>的写入：密码错误时累加失败计数
     * （见下方 {@code recordLoginFailure}）。但紧接着就会抛出
     * {@link BizException} 把登录失败告诉调用方。
     *
     * <p>而 {@code BizException} 继承 {@code RuntimeException} —— 这是<b>刻意</b>的设计
     * （见其类注释："必须可触发事务回滚"），对绝大多数业务操作都正确。
     * 但在这里，它会导致<b>失败计数的写入被一并回滚</b>，后果是：
     * <pre>
     *   每次登录失败 → 计数 +1 → 抛异常 → 事务回滚 → 计数回到 0
     *   ⇒ 失败计数永远到不了阈值 ⇒ 账号永远不会被锁定
     *   ⇒ 暴力破解防护 100% 失效，而日志里"剩余尝试次数"看起来还在正常递减
     * </pre>
     * 这个 bug <b>不会报任何错</b>，接口行为完全正常，只有去查数据库才会发现
     * {@code fail_count} 始终是 0。本次就是靠比对日志与数据库才发现的：
     * 日志连续三次都显示"剩余尝试次数=4"，而数据库里 {@code fail_count=0}。
     *
     * <h3>为什么不是"用新事务写计数"</h3>
     * {@code REQUIRES_NEW} 也能解决，但它要在外层事务持有连接的同时再占用一个连接，
     * 而登录失败恰恰是攻击者能高频触发的路径 —— <b>会给暴力破解提供一个
     * 消耗连接池的放大器</b>。用 {@code noRollbackFor} 则在同一事务内提交，无额外开销。
     *
     * <h3>这样做会不会让别的错误也不回滚</h3>
     * 本方法内抛出的 {@code BizException} 只有两种：凭据错误与用户不存在。
     * 前者需要计数落库（正是本注解的目的）；后者没有任何写入需要回滚。
     * 其余的失败（数据库异常等）都不是 {@code BizException}，回滚行为不受影响。
     */
    @Transactional(noRollbackFor = BizException.class)
    public LoginResult login(LoginCommand command) {
        long tenantId = resolveTenantId(command);
        Username username = Username.of(command.username());

        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            dummyVerify(command.password());
            log.warn("登录失败：用户不存在 tenantId={} username={} ip={}",
                    tenantId, username.value(), command.loginIp());
            throw new BizException(CommonErrorCode.BAD_CREDENTIALS);
        }

        User user = found.get();

        // 先判状态：锁定/停用的用户即使密码正确也拒绝，且不累加失败计数
        // （否则"锁定"会变成"永久拒绝"——计数继续涨，解锁后立刻又超阈值）
        try {
            user.assertCanLogin();
        } catch (RuntimeException ex) {
            log.warn("登录被拒：用户状态不允许 tenantId={} username={} status={}",
                    tenantId, username.value(), user.status());
            throw ex;
        }

        if (!passwordEncoder.matches(command.password(), user.password())) {
            int remaining = user.recordLoginFailure(DEFAULT_MAX_FAIL, DEFAULT_LOCK_MINUTES);
            // 这一步的写入<b>必须真正落库</b>，否则锁定机制形同虚设。
            // 能保证这一点的不是这里的 save()，而是方法上的
            // @Transactional(noRollbackFor = BizException.class) —— 见方法注释。
            userRepository.save(user);
            log.warn("登录失败：密码错误 tenantId={} username={} 剩余尝试次数={}（达 0 即锁定）",
                    tenantId, username.value(), remaining);
            // 同样返回统一的凭据错误。把"剩余次数"放进响应会泄露"该用户确实存在"，
            // 因此只记日志、不返回给调用方。
            throw new BizException(CommonErrorCode.BAD_CREDENTIALS);
        }

        // 密码正确 → 记录成功（内部会处理"锁定期已过则自动解锁"）
        user.recordLoginSuccess(command.loginIp());

        // 哈希算法/cost 升级：登录是唯一能拿到明文密码的时机，
        // 错过这次就要等用户下次登录，因此在这里顺手重算。
        if (passwordEncoder.needsRehash(user.password())) {
            PasswordHash rehashed = passwordEncoder.encode(command.password());
            user.upgradePasswordHash(rehashed);
            log.info("已升级密码哈希算法参数 tenantId={} username={}", tenantId, username.value());
        }

        userRepository.save(user);

        // 权限必须在用户状态落库后再解析，否则可能读到"锁定态"的旧缓存
        CachedPermissions permissions = permissionResolver.refresh(tenantId, user.id().value());
        CurrentUser currentUser = new CurrentUser(user.id().value(), tenantId, user.username().value());
        TokenIssuerPort.IssuedToken token =
                tokenIssuer.issue(currentUser, permissions.roleKeys());

        log.info("登录成功 tenantId={} userId={} username={} ip={} 权限码数={} 超管={}",
                tenantId, user.id().value(), username.value(), command.loginIp(),
                permissions.permissionCodes().size(), permissions.superAdmin());

        return new LoginResult(
                token.accessToken(),
                token.expiresInSeconds(),
                toDTO(user),
                // 用 effectivePermissionCodes 而非 permissionCodes：
                // 超管返回通配符 "*"，否则前端会因为拿到空集合而隐藏全部按钮
                permissions.effectivePermissionCodes(),
                permissions.roleKeys());
    }

    // ==================================================================
    // 当前用户
    // ==================================================================

    /** 当前登录用户信息。 */
    public CurrentUserDTO currentUserInfo() {
        CurrentUser current = currentUserPort.requireCurrentUser();
        User user = userRepository.findById(com.webadmin.domain.shared.UserId.of(current.userId()))
                .orElseThrow(() -> new BizException(IamErrorCode.USER_NOT_FOUND));
        return toDTO(user);
    }

    /**
     * 当前用户的菜单列表（<b>扁平结构</b>，由前端建树）。
     *
     * <p>为什么返回扁平列表而不是树：树结构在传输上更"好看"，
     * 但它把"建树规则"固化在了后端接口里；而扁平结构让前端可以按需组合
     * （面包屑、菜单高亮、权限树回显都需要同一份数据的不同视图）。
     * 菜单数据量在千级以内，前端建树的成本可忽略。
     */
    public List<MenuDTO> currentUserMenus() {
        CurrentUser current = currentUserPort.requireCurrentUser();
        CachedPermissions permissions =
                permissionResolver.resolve(current.tenantId(), current.userId());

        List<Menu> menus;
        if (permissions.superAdmin()) {
            // 超管看到全部可用菜单：不查 role_menu，避免"新菜单忘了授权给超管"的经典问题
            menus = menuRepository.findAllUsable();
        } else {
            Set<MenuId> menuIds = collectMenuIds(current.userId());
            menus = menuIds.isEmpty() ? List.of() : menuRepository.findAllByIds(menuIds);
        }

        return menus.stream()
                .filter(Menu::isUsable)
                .map(MenuDTO::from)
                .toList();
    }

    /**
     * 当前用户的权限码集合（前端刷新页面后重新拉取，用于重建按钮权限）。
     *
     * <p>与登录接口返回的是同一套值，包含超管通配符 {@code "*"} ——
     * 两处必须一致，否则会出现"登录时按钮可见、刷新后消失"这种诡异现象。
     */
    public Set<String> currentUserPermissions() {
        CurrentUser current = currentUserPort.requireCurrentUser();
        return permissionResolver.resolve(current.tenantId(), current.userId())
                .effectivePermissionCodes();
    }

    // ==================================================================
    // 内部方法
    // ==================================================================

    /**
     * 确定本次登录应该在哪个租户里进行。
     *
     * <p>优先用命令里的租户编码（登录表单填写），否则回退到上下文中的租户
     * （由网关或请求头注入）。两者都没有则直接拒绝 ——
     * 不猜、不默认到某个租户，因为"登录到错误的租户"比"登录失败"危险得多。
     */
    private long resolveTenantId(LoginCommand command) {
        if (command.tenantCode() != null && !command.tenantCode().isBlank()) {
            return tenantRepositoryLookup(command.tenantCode());
        }
        return TenantContext.require();
    }

    /**
     * 按租户编码查租户 ID。
     *
     * <p>⚠️ 当前实现直接回退到上下文租户，因为 {@code TenantQueryPort} 是读侧端口、
     * 尚未在此处注入。P1 收尾时应改为真正按 code 查询。
     * 保留这个独立方法而不是内联，是为了让这处待办有明确的落点。
     */
    private long tenantRepositoryLookup(String tenantCode) {
        // TODO(P1 收尾)：注入 TenantQueryPort，按 tenantCode 查出 tenantId 并校验租户状态为 ACTIVE。
        //  当前之所以不直接内联查库，是因为登录接口在 Security 之外，
        //  此时租户上下文尚未建立，而 iam_tenant 虽在忽略名单中可直接查，
        //  但仍应通过既有的 TenantQueryPort 复用租户状态校验逻辑，避免两处判断不一致。
        log.warn("登录请求携带了 tenantCode={}，但按编码解析租户尚未实现，回退到上下文租户", tenantCode);
        return TenantContext.require();
    }

    /**
     * 对不存在的用户执行一次"假校验"。
     *
     * <p>目的：消除<b>响应耗时侧信道</b>。若用户不存在时立即返回，而存在时要做
     * 一次 BCrypt 校验（约 50~100ms），攻击者只需测量响应时间就能判断用户名是否存在 ——
     * 这会让"统一错误码"的防护形同虚设。
     *
     * <p>这里用一次真实的编码操作消耗近似时间。选 {@code encode} 而非
     * {@code matches}，是因为前者不依赖任何哈希值，实现最简。
     */
    private void dummyVerify(String rawPassword) {
        try {
            passwordEncoder.encode(rawPassword == null ? "dummy" : rawPassword);
        } catch (RuntimeException ex) {
            // 假校验本身失败无任何影响，绝不能让它改变对外行为
            log.debug("假校验执行异常（已忽略）：{}", ex.getMessage());
        }
    }

    private Set<MenuId> collectMenuIds(long userId) {
        Set<MenuId> menuIds = new LinkedHashSet<>();
        roleRepository.findByUserId(userId).stream()
                .filter(com.webadmin.domain.iam.model.role.Role::isUsable)
                .forEach(role -> menuIds.addAll(role.menuIds()));
        return menuIds;
    }

    private CurrentUserDTO toDTO(User user) {
        return new CurrentUserDTO(
                user.id().value(),
                user.tenantId().value(),
                user.username().value(),
                user.nickname(),
                user.avatar(),
                user.email(),
                user.phone(),
                user.deptId(),
                user.status().name(),
                user.loginIp(),
                user.loginTime());
    }
}
