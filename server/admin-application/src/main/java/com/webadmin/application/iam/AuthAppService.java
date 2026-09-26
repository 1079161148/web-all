package com.webadmin.application.iam;

import com.webadmin.application.iam.command.LoginCommand;
import com.webadmin.application.iam.dto.CurrentUserDTO;
import com.webadmin.application.iam.dto.LoginResult;
import com.webadmin.application.iam.dto.MenuDTO;
import com.webadmin.application.iam.dto.SessionView;
import com.webadmin.application.iam.port.PermissionCachePort.CachedPermissions;
import com.webadmin.application.iam.port.CaptchaPort;
import com.webadmin.application.iam.port.LoginRememberPort;
import com.webadmin.application.iam.port.RefreshTokenPort;
import com.webadmin.application.iam.port.SessionRegistryPort;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final SessionRegistryPort sessionRegistry;
    private final RefreshTokenPort refreshTokenStore;
    private final com.webadmin.domain.iam.repository.TenantRepository tenantRepository;
    private final Clock clock;
    private final CaptchaPort captchaPort;
    private final LoginRememberPort loginRememberPort;
    private final UserAppService userAppService;

    /**
     * 图形验证码开关（默认开启）。
     *
     * <p>集成测试关闭：IT 会反复登录（每个用例都要建立会话），而验证码是
     * 一次性凭证且答案只在图片里 —— 让测试去"识图"是荒谬的。
     * 这与 {@code webadmin.security.rate-limit.enabled} 的处理同因：
     * <b>测试关注的是业务语义，不是人机对抗</b>。验证码本身的语义
     * 由 {@code CaptchaLoginIT} 在开启状态下专门验证。
     */
    @Value("${webadmin.security.captcha.enabled:true}")
    private boolean captchaEnabled;

    /**
     * 免登录天数白名单（默认 1/7/30）。
     *
     * <p>Spring 会把逗号分隔的配置转成 {@link List}。
     * 这里是<b>唯一</b>决定"可以记住多久"的地方 —— 前端选项、请求校验、
     * TTL 换算全部以它为准，避免两处各写一套导致"前端只能选 7 天、
     * 后端却接受 30 天"这类不一致。
     */
    @Value("${webadmin.security.jwt.remember-days:1,7,30}")
    private List<Integer> allowedRememberDays = List.of(1, 7, 30);

    /**
     * 会话与刷新令牌的有效期（默认 7 天）。
     *
     * <p>⚠️ 刻意用字段注入而不是构造器注入：本类用 Lombok
     * {@code @RequiredArgsConstructor} 生成构造器，而 Lombok 不会把
     * {@code @Value} 转移到构造器参数上 —— 写在 final 字段上会导致
     * Spring 尝试按名字解析一个叫 "sessionTtl" 的 Bean，启动直接失败。
     * 配置类字段用字段注入是这里的务实解。
     */
    @Value("${webadmin.security.jwt.refresh-token-ttl:P7D}")
    private Duration sessionTtl;

    /** 刷新令牌的随机源。静态共享即可：SecureRandom 线程安全，且实例化有成本。 */
    private static final SecureRandom REFRESH_RANDOM = new SecureRandom();

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
        // ⚠️ 验证码校验放在<b>最前面</b>，早于任何查库与密码比对：
        //   1. 防爆破的主要收益来自"每次都挡在数据库之前"，越早越省资源
        //   2. 若放在密码校验之后，攻击者就能用错误验证码 + 正确密码的组合
        //      探测"验证码是否必须"以及"密码对不对"（错误信息差异会泄露）
        //   3. 验证码错误<b>不累加密码失败计数</b>：否则任何人只要拿着别人的
        //      用户名反复输错验证码，就能把该账号锁死（拒绝服务）。挡爆破靠
        //      一次性消费 + IP 限流，不靠账号锁定
        if (captchaEnabled
                && !captchaPort.verifyAndConsume(command.captchaId(), command.captchaCode())) {
            log.warn("登录失败：验证码校验未通过 ip={}", command.loginIp());
            throw new BizException(CommonErrorCode.CAPTCHA_INVALID);
        }

        // 免登录天数：客户端传值必须过白名单（理由见 resolveRememberDays）
        int rememberDays = resolveRememberDays(command.rememberDays());

        long tenantId = resolveTenantId(command);
        assertTenantAccessible(tenantId);
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

        // ---- 建立会话（② 会话治理）----
        // 版本号取"刚保存过的聚合"的当前值。登录<b>不提升</b>版本号：
        // 否则每次登录都会把该用户其它设备的令牌全部踢掉（改密码才提升）
        long tokenVersion = user.tokenVersion();
        String sessionId = UUID.randomUUID().toString();
        String familyId = UUID.randomUUID().toString();
        String refreshToken = newRefreshToken();

        CurrentUser currentUser = new CurrentUser(
                user.id().value(), tenantId, user.username().value(), sessionId);
        TokenIssuerPort.IssuedToken token =
                tokenIssuer.issue(currentUser, permissions.roleKeys(), tokenVersion);

        Instant now = clock.instant();
        // 会话与刷新令牌的生命周期：记住 N 天时按用户选择，否则用默认 TTL。
        // ⚠️ 访问令牌仍然短命（token.expiresInSeconds()）—— "免登录"只延长
        // 刷新令牌与会话，而长期凭证的唯一用途是"换短令牌"。
        // 若把它做成"长命访问令牌"，一旦泄漏就是长达 30 天的直接业务访问权限
        Duration sessionLifetime = rememberDays > 0 ? Duration.ofDays(rememberDays) : sessionTtl;

        // 会话 TTL 覆盖刷新令牌的整个生命周期（见 SessionRegistryPort 的 TTL 语义）：
        // 会话代表"这台设备还处于登录状态"，访问令牌只是它的短期凭证
        sessionRegistry.register(new SessionRegistryPort.SessionRecord(
                sessionId, tenantId, user.id().value(), user.username().value(),
                tokenVersion, command.loginIp(), null, now, now), sessionLifetime);
        // 刷新令牌只存哈希：Redis 泄漏（导出/快照/误授权）不等于会话被送走，与存密码同理
        refreshTokenStore.save(sha256Hex(refreshToken),
                new RefreshTokenPort.RefreshRecord(familyId, tenantId, user.id().value(), sessionId),
                sessionLifetime);
        // 记住天数由服务端记住：刷新时才能恢复同样长度的 Cookie（见 LoginRememberPort）
        loginRememberPort.mark(tenantId, sessionId, rememberDays, sessionLifetime);

        log.info("登录成功 tenantId={} userId={} username={} ip={} 权限码数={} 超管={} 会话={} 免登录天数={}",
                tenantId, user.id().value(), username.value(), command.loginIp(),
                permissions.permissionCodes().size(), permissions.superAdmin(), sessionId, rememberDays);

        return new LoginResult(
                token.accessToken(),
                token.expiresInSeconds(),
                refreshToken,
                sessionLifetime.toSeconds(),
                toDTO(user),
                // 用 effectivePermissionCodes 而非 permissionCodes：
                // 超管返回通配符 "*"，否则前端会因为拿到空集合而隐藏全部按钮
                permissions.effectivePermissionCodes(),
                permissions.roleKeys(),
                rememberDays);
    }

    /**
     * 校验并归一化"免登录天数"。
     *
     * <h3>为什么必须白名单</h3>
     * 这个字段会被直接换算成会话/刷新令牌的 TTL。若原样采信客户端传值，
     * <b>一次请求就能给自己签发一个"3650 天有效"的会话</b> ——
     * 这是纯粹的权限提升：请求体里的一个数字换来了长期访问权。
     * 因此非法值一律拒绝（而不是"取最接近的合法值"）：
     * 静默纠正会让攻击者以为成功了，也让日志失去异常信号。
     *
     * @return 合法天数；null/非法 → 0（不记住）
     */
    private int resolveRememberDays(Integer requested) {
        if (requested == null) {
            return 0;
        }
        if (requested == 0) {
            return 0;
        }
        if (allowedRememberDays.contains(requested)) {
            return requested;
        }
        log.warn("登录被拒：免登录天数不在白名单内 requested={} 白名单={}", requested, allowedRememberDays);
        throw new BizException(CommonErrorCode.PARAM_INVALID,
                "免登录天数仅支持 " + allowedRememberDays);
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
    // 会话：注销 / 刷新 / 在线列表
    // ==================================================================

    /**
     * 注销当前会话（退出登录）。幂等：会话已不存在时静默成功。
     *
     * <p>必须同时吊销刷新令牌：只删会话键的话，刷新令牌仍能换出新访问令牌，
     * "退出登录"就形同虚设。
     */
    public void logout() {
        CurrentUser current = currentUserPort.requireCurrentUser();
        if (current.sessionId() == null || current.sessionId().isBlank()) {
            // 无会话上下文（旧版令牌）：没有可注销的东西。
            // 幂等成功比报错合理 —— 退出登录不应因后端状态而失败
            return;
        }
        sessionRegistry.revoke(current.tenantId(), current.userId(), current.sessionId());
        refreshTokenStore.revokeBySession(current.tenantId(), current.userId(), current.sessionId());
        log.info("用户退出登录 tenantId={} userId={} 会话={}",
                current.tenantId(), current.userId(), current.sessionId());
    }

    /**
     * 用刷新令牌换取新的令牌对（<b>轮换 + 重放检测</b>）。
     *
     * <h3>四道检查，各有明确的失败语义</h3>
     * <ol>
     *   <li><b>令牌存在</b>：未知令牌一律拒绝（不区分"伪造"与"已过期"，
     *       避免向调用方泄露令牌状态）</li>
     *   <li><b>未被轮换过</b>：命中"已用"标记 = 同一个令牌出现两个持有者。
     *       无法区分攻击者与用户重试，按最坏情况处理 ——
     *       吊销整个令牌族 + 踢会话，让双方都重新登录</li>
     *   <li><b>会话仍然存在</b>：会话被注销/被踢后，刷新令牌即使没到期也必须作废 ——
     *       否则"踢人"会被刷新操作轻易绕过</li>
     *   <li><b>用户仍可登录</b>：停用/锁定/删除的用户不允许续期，
     *       并顺手清掉其残余令牌</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public LoginResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BizException(IamErrorCode.REFRESH_TOKEN_INVALID);
        }
        String hash = sha256Hex(rawRefreshToken);
        RefreshTokenPort.RefreshRecord record = refreshTokenStore.find(hash)
                .orElseThrow(() -> new BizException(IamErrorCode.REFRESH_TOKEN_INVALID));

        // ⚠️ 续期必须在<b>租户上下文</b>里执行：refresh 是公开端点（请求携带的是
        // 刷新令牌而非访问令牌），JwtAuthenticationFilter 因此不会设置
        // TenantContext，而下面的会话查询、userRepository.findById、权限解析
        // 都要经过租户拦截器 —— 缺上下文时 MyBatis 直接抛
        // IllegalStateException（实测：刷新接口 500，进而使"访问令牌过期后
        // 静默刷新必然失败 → 前端拿到 401"）。
        // 租户来源是服务端自己的刷新令牌记录（可信数据，不取自客户端输入）；
        // callWith 自带 try/finally 恢复，线程复用时不会残留租户上下文。
        return TenantContext.callWith(record.tenantId(), () -> completeRefresh(record, hash));
    }

    /** 续期主体（运行在 {@link #refresh} 建立的租户上下文内）。 */
    private LoginResult completeRefresh(RefreshTokenPort.RefreshRecord record, String hash) {
        if (refreshTokenStore.isRotated(hash)) {
            refreshTokenStore.revokeFamily(record.familyId());
            sessionRegistry.revoke(record.tenantId(), record.userId(), record.sessionId());
            log.warn("检测到刷新令牌重放，已吊销整个令牌族并强制下线相关会话 userId={} familyId={}",
                    record.userId(), record.familyId());
            throw new BizException(IamErrorCode.REFRESH_TOKEN_REUSED);
        }

        SessionRegistryPort.SessionRecord session = sessionRegistry
                .find(record.tenantId(), record.userId(), record.sessionId())
                .orElseThrow(() -> new BizException(IamErrorCode.REFRESH_TOKEN_INVALID));

        User user = userRepository.findById(com.webadmin.domain.shared.UserId.of(record.userId()))
                .orElseThrow(() -> new BizException(IamErrorCode.REFRESH_TOKEN_INVALID));
        try {
            user.assertCanLogin();
        } catch (RuntimeException ex) {
            revokeAllSessionState(record.tenantId(), record.userId());
            throw new BizException(IamErrorCode.USER_ILLEGAL_STATE, ex.getMessage());
        }

        long tokenVersion = user.tokenVersion();
        CachedPermissions permissions = permissionResolver.resolve(record.tenantId(), record.userId());

        // 免登录天数<b>从服务端标记回读</b>，不看本次请求的任何参数：
        // 否则刷新就成了"把不记住的会话升级成记住 30 天"的提权入口
        // （登录时已白名单校验过，这里读到的值天然可信）
        int rememberDays = loginRememberPort.find(record.tenantId(), record.sessionId()).orElse(0);
        Duration sessionLifetime = rememberDays > 0 ? Duration.ofDays(rememberDays) : sessionTtl;

        // 轮换：旧令牌标记为"已用"（保留用于重放检测），新令牌入族（family 不变）
        String newRefreshToken = newRefreshToken();
        refreshTokenStore.markRotated(hash, sessionLifetime);
        refreshTokenStore.save(sha256Hex(newRefreshToken),
                new RefreshTokenPort.RefreshRecord(
                        record.familyId(), record.tenantId(), record.userId(), record.sessionId()),
                sessionLifetime);

        // 会话滚动续期 + 版本号对齐：
        // 若用户版本在会话存活期间被提升过（如管理员强制下线又没踢干净），
        // 新令牌带新版本，会话记录必须同步，否则下一次请求会被判成"版本不一致"而登出
        Instant now = clock.instant();
        sessionRegistry.register(new SessionRegistryPort.SessionRecord(
                record.sessionId(), record.tenantId(), record.userId(), user.username().value(),
                tokenVersion, session.ip(), session.userAgent(), session.loginTime(), now),
                sessionLifetime);
        // 标记随会话一起续期：否则它会先于会话过期，导致后续刷新悄悄降级成会话级 Cookie
        loginRememberPort.mark(record.tenantId(), record.sessionId(), rememberDays, sessionLifetime);

        CurrentUser currentUser = new CurrentUser(
                record.userId(), record.tenantId(), user.username().value(), record.sessionId());
        TokenIssuerPort.IssuedToken token =
                tokenIssuer.issue(currentUser, permissions.roleKeys(), tokenVersion);

        log.info("刷新令牌轮换成功 tenantId={} userId={} 会话={} 族={} 免登录天数={}",
                record.tenantId(), record.userId(), record.sessionId(), record.familyId(), rememberDays);
        return new LoginResult(
                token.accessToken(),
                token.expiresInSeconds(),
                newRefreshToken,
                sessionLifetime.toSeconds(),
                toDTO(user),
                permissions.effectivePermissionCodes(),
                permissions.roleKeys(),
                rememberDays);
    }

    /** 当前用户的在线会话（按最后活跃倒序），并标记哪个是当前会话。 */
    public List<SessionView> listSessions() {
        CurrentUser current = currentUserPort.requireCurrentUser();
        return sessionRegistry.list(current.tenantId(), current.userId()).stream()
                .map(session -> new SessionView(
                        session.sessionId(),
                        session.ip(),
                        session.userAgent(),
                        session.loginTime(),
                        session.lastActive(),
                        session.sessionId().equals(current.sessionId())))
                .toList();
    }

    /**
     * 注销自己的某个会话（"在那台设备上退出登录"）。
     *
     * <p>会话键以 userId 分段，因此传入<b>别人的</b>会话 ID 时删除的是
     * 一个不存在的键 —— 天然无操作，不会误伤他人，也无需为此做存在性检查
     * （检查反而会泄露"该会话是否存在"）。
     */
    public void revokeSession(String sessionId) {
        CurrentUser current = currentUserPort.requireCurrentUser();
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(IamErrorCode.USER_ILLEGAL_STATE, "会话 ID 不能为空");
        }
        if (sessionId.equals(current.sessionId())) {
            // 与退出登录语义相同，收敛到一个入口
            logout();
            return;
        }
        sessionRegistry.revoke(current.tenantId(), current.userId(), sessionId);
        refreshTokenStore.revokeBySession(current.tenantId(), current.userId(), sessionId);
        log.info("注销用户会话 tenantId={} userId={} 会话={}", current.tenantId(), current.userId(), sessionId);
    }

    /**
     * 当前用户修改自己的密码（个人中心）。
     *
     * <h3>为什么要校验原密码</h3>
     * "已经登录"不等于"可以改密码"。若只凭会话就允许改密，
     * 那么一次 XSS、一个被劫持的标签页、甚至一次"忘了锁屏"
     * 都能让攻击者<b>把账号彻底据为己有</b>（改密后真正的用户再也进不来）。
     * 要求原密码把这一步重新锚定到"本人知道凭据"上 ——
     * 这是所有主流系统在此处的共同做法。
     *
     * <h3>为什么复用 {@link UserAppService#resetPassword}</h3>
     * 它已经串起了改密必须连带的三件事，缺一不可：
     * <ol>
     *   <li><b>密码策略校验</b>（长度/弱片段/不得含用户名）——
     *       另写一份迟早与管理员重置的规则漂移</li>
     *   <li><b>提升令牌版本号</b>：旧访问令牌立即失效，
     *       且是<b>持久</b>生效（不依赖 Redis 是否存活）</li>
     *   <li><b>吊销全部会话与刷新令牌</b>：改密后所有设备都必须重新登录 ——
     *       这正是用户改密时想要的语义（"把别处登录的都踢掉"）</li>
     * </ol>
     *
     * <p>代价是当前设备也会被踢（返回后需重新登录）。这是<b>刻意保留</b>的：
     * 若这里为当前会话网开一面，就出现"改密后攻击者的会话仍在"的窗口，
     * 而那恰恰是最需要消除的情况。
     */
    @Transactional
    public void changeOwnPassword(String oldPassword, String newPassword) {
        CurrentUser current = currentUserPort.requireCurrentUser();
        User user = userRepository.findById(com.webadmin.domain.shared.UserId.of(current.userId()))
                .orElseThrow(() -> new BizException(IamErrorCode.USER_NOT_FOUND));

        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.password())) {
            // 错误信息刻意与登录失败同源（BAD_CREDENTIALS）：它们表达的是同一件事 ——
            // "你不具备这个账号的凭据"。单独造一个"原密码错误"码没有额外价值，
            // 反而会让错误码清单越来越长
            log.warn("修改密码失败：原密码不正确 tenantId={} userId={}",
                    current.tenantId(), current.userId());
            throw new BizException(CommonErrorCode.BAD_CREDENTIALS);
        }

        // 复用管理员重置的那条链路：策略校验 → 编码 → 提升版本号 → 吊销会话
        userAppService.resetPassword(current.userId(), newPassword);
        log.info("用户修改了自己的密码 tenantId={} userId={}（全部会话已失效，需重新登录）",
                current.tenantId(), current.userId());
    }

    /** 吊销某用户的全部会话状态（会话 + 刷新令牌）。供"停用/强退/检测到异常"使用。 */
    private void revokeAllSessionState(long tenantId, long userId) {
        sessionRegistry.revokeAll(tenantId, userId);
        refreshTokenStore.revokeAllOfUser(tenantId, userId);
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
    /**
     * 登录前校验租户可用：过期 / 停用 / 关闭的租户拒绝登录。
     *
     * <h3>这是「过期自动冻结」真正生效的执行点</h3>
     * 定时任务负责把 EXPIRED 状态<b>落库</b>（供列表、报表与事件副作用使用）；
     * 而登录是否放行走的是聚合的 {@code assertAccessible} ——
     * 它按有效期<b>实时</b>判定，不依赖任务是否执行过。
     * 两层各管一件事：即使调度延迟甚至调度器宕机，过期租户也进不来；
     * 反过来说，状态落库只是"追认"，不是防线本身。
     */
    private void assertTenantAccessible(long tenantId) {
        tenantRepository.findById(com.webadmin.domain.shared.TenantId.ofPersisted(tenantId))
                .orElseThrow(() -> new BizException(IamErrorCode.TENANT_NOT_FOUND, "租户不存在"))
                .assertAccessible(clock);
    }

    private void dummyVerify(String rawPassword) {
        try {
            passwordEncoder.encode(rawPassword == null ? "dummy" : rawPassword);
        } catch (RuntimeException ex) {
            // 假校验本身失败无任何影响，绝不能让它改变对外行为
            log.debug("假校验执行异常（已忽略）：{}", ex.getMessage());
        }
    }

    // ==================================================================
    // 会话治理辅助
    // ==================================================================

    /**
     * 生成刷新令牌原文：256 位随机数的 base64url（43 字符，无填充）。
     *
     * <p>为什么是不透明随机串而不是 JWT：刷新令牌本来就<b>必须查服务端</b>
     * （要判断轮换与重放、要能撤销），躲不开查询就没必要承担 JWT 的
     * 体积与"无法真正撤销"的代价。详见 {@link RefreshTokenPort} 的类注释。
     */
    private static String newRefreshToken() {
        byte[] bytes = new byte[32];
        REFRESH_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 刷新令牌的存储形态：SHA-256 十六进制。原文不落任何存储。
     *
     * <p>代价是每次刷新多一次摘要运算（微秒级）；收益是 Redis 被导出/快照/误授权时，
     * 攻击者拿到的只是不可逆摘要，无法冒充任何用户。
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JVM 规范要求所有实现提供 SHA-256，走到这里说明环境异常，快速失败
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", ex);
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
