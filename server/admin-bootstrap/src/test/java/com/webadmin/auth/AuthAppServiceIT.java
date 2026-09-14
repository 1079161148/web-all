package com.webadmin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.webadmin.application.iam.AuthAppService;
import com.webadmin.application.iam.command.LoginCommand;
import com.webadmin.application.iam.dto.LoginResult;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.exception.IllegalUserStateException;
import com.webadmin.domain.iam.model.role.RoleKey;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.UserStatus;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.iam.port.PasswordEncoderPort;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 认证应用服务的集成测试。
 *
 * <h3>为什么必须是集成测试（而不是 mock 掉仓储的单元测试）</h3>
 * 本类要验证的<b>核心性质是"写入是否真正提交"</b>。
 * 这个性质完全由 <b>Spring 事务语义</b>决定，与业务逻辑无关 ——
 * 把仓储 mock 掉之后，"事务有没有回滚"这件事根本不在被测范围内，
 * 测试会无条件通过，包括在 bug 存在时也通过。
 *
 * <p>这正是本测试诞生的原因：{@code AuthAppService.login} 上的
 * {@code noRollbackFor = BizException.class} 缺失时，
 * 失败计数的写入会被抛出的异常回滚。<b>该 bug 不会报任何错</b>，
 * 接口行为、日志输出全都正常，只有查数据库才会发现 {@code fail_count} 始终是 0，
 * 表现为"暴力破解防护完全失效"。
 *
 * <h3>刻意不加 {@code @Transactional}</h3>
 * Spring 测试默认可以用 {@code @Transactional} 让每个测试方法结束后回滚，
 * 从而获得隔离性。但<b>这里绝对不能加</b>：
 * 外层测试事务会把被测方法的事务"吸收"进来，于是"提交"根本不会发生，
 * 断言读到的是同一事务内的未提交数据 —— 测试会通过，但它验证的
 * "<b>跨事务可见性</b>"恰恰消失了。<b>用错误的方式获得隔离性，
 * 会让测试失去它唯一的价值。</b>
 *
 * <p>因此这里改为在每个测试方法后<b>显式复位账号状态</b>来获得隔离性。
 */
@SpringBootTest(properties = {
        // 测试专用的初始密码。写死在注解里而不是复用 dev 默认值，
        // 是为了让"测试期望的密码"与"实际创建的密码"在同一处可见 ——
        // 否则改了 application.yml 的默认值会让测试以"密码错误"的形式失败，
        // 而报错信息完全指不到真正的原因。
        "webadmin.security.init-admin.password=Test@123456",
        "webadmin.security.init-admin.enabled=true",
        "spring.main.banner-mode=off"
})
class AuthAppServiceIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String ADMIN_USERNAME = "admin";
    private static final String CORRECT_PASSWORD = "Test@123456";
    private static final String WRONG_PASSWORD = "Wrong@000000";

    @Autowired
    private AuthAppService authAppService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoderPort passwordEncoder;

    @BeforeEach
    void bindTenantContext() {
        // 仓储查询会被租户拦截器加上 tenant_id 条件，
        // 而 TenantContext 在非 HTTP 场景下是空的（require() 会抛异常）。
        // 测试必须像定时任务一样显式建立上下文 —— <b>不给测试开后门</b>，
        // 否则测试就绕过了真实代码路径必须满足的约束。
        TenantContext.set(TENANT_ID);
        // 在**开始前**也复位一次，而不只在结束后复位。
        // 理由：若上一次运行崩溃/被中断，账号可能停留在 LOCKED 状态，
        // 于是下一次运行的所有用例都会莫名其妙地报"账号已被锁定"——
        // 而错误信息与真正的原因（上次没跑完）毫无关联。
        // 测试的隔离性必须**不依赖"上次跑得很干净"**这个假设。
        resetAdminState();
    }

    @AfterEach
    void clearTenantContext() {
        resetAdminState();
        TenantContext.clear();
    }

    /**
     * 复位超管账号的全部可变状态。
     *
     * <p>用 {@code resetPassword} 而不是直接 UPDATE：它是唯一能一次性复位
     * 全部可变状态的聚合方法（清零 fail_count、清除 lock_until、LOCKED → ACTIVE），
     * 且复位动作本身也走聚合不变量。
     *
     * <p>⚠️ 这里能生效的前提是 {@code UserPO.lockUntil} 标了
     * {@code updateStrategy = ALWAYS}。否则 MP 会把 null 的 lock_until 从
     * SET 子句里跳过，复位看起来成功、实际上锁还在 —— 本测试第一次运行时
     * 就是因此连续 3 个用例失败。
     */
    private void resetAdminState() {
        userRepository.findByUsername(Username.of(ADMIN_USERNAME)).ifPresent(user -> {
            user.resetPassword(passwordEncoder.encode(CORRECT_PASSWORD));
            userRepository.save(user);
        });
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("正确密码可以正常登录，并签发令牌与权限")
    void should_login_successfully_with_correct_password() {
        LoginResult result = login(ADMIN_USERNAME, CORRECT_PASSWORD);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.expiresInSeconds()).isPositive();
        assertThat(result.user().username()).isEqualTo(ADMIN_USERNAME);
        assertThat(result.user().tenantId()).isEqualTo(TENANT_ID);
        // 超管应拿到通配符权限，而不是一长串权限码：
        // 通配符让"新增权限点后超管自动拥有"成为事实，无需维护一份快照
        assertThat(result.roles()).contains(RoleKey.SUPER_ADMIN);
        assertThat(result.permissions()).containsExactly("*");

        // 成功登录必须清零失败计数并落库
        assertThat(reloadAdmin().failCount()).isZero();
        assertThat(reloadAdmin().loginTime()).isNotNull();
    }

    // ==================================================================
    // 回归测试：失败计数必须落库（本类的核心）
    // ==================================================================

    @Test
    @DisplayName("密码错误时，失败计数必须在异常抛出后依然落库")
    void should_persist_fail_count_after_wrong_password() {
        assertThatThrownBy(() -> login(ADMIN_USERNAME, WRONG_PASSWORD))
                .isInstanceOf(BizException.class);

        // ★ 关键断言：这里是**重新查库**，读的是另一个事务已提交的数据。
        //   若 AuthAppService.login 上的 noRollbackFor 缺失，
        //   计数会随异常一起被回滚，此处将得到 0 而失败。
        assertThat(reloadAdmin().failCount())
                .as("失败计数必须在异常抛出后依然持久化，否则账号永远不会被锁定")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("连续失败达阈值后账号被锁定，且锁定期间正确密码也被拒绝")
    void should_lock_account_after_reaching_threshold() {
        int maxFail = AuthAppService.DEFAULT_MAX_FAIL;

        for (int attempt = 0; attempt < maxFail; attempt++) {
            // 需要把异常吞掉：前几次是"密码错误"，阈值那次之后账号已锁定
            try {
                login(ADMIN_USERNAME, WRONG_PASSWORD);
            } catch (BizException | IllegalUserStateException expected) {
                // 预期内
            }
        }

        User locked = reloadAdmin();
        assertThat(locked.failCount()).isEqualTo(maxFail);
        assertThat(locked.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(locked.lockUntil()).isNotNull();

        // 锁定期间即使密码正确也必须拒绝 ——
        // 否则"锁定"只是计数上的装饰，攻击者仍可继续尝试
        assertThatThrownBy(() -> login(ADMIN_USERNAME, CORRECT_PASSWORD))
                .isInstanceOf(IllegalUserStateException.class);
    }

    // ==================================================================
    // 安全性质：防用户名枚举
    // ==================================================================

    @Test
    @DisplayName("用户不存在与密码错误必须返回同一个业务码（防用户名枚举）")
    void should_return_same_error_code_for_unknown_username() {
        BizException wrongPassword = (BizException) catchThrowable(
                () -> login(ADMIN_USERNAME, WRONG_PASSWORD));
        BizException unknownUser = (BizException) catchThrowable(
                () -> login("no-such-user-at-all", WRONG_PASSWORD));

        assertThat(wrongPassword).isNotNull();
        assertThat(unknownUser).isNotNull();

        // 若两者错误码不同，攻击者就能用登录接口<b>枚举出系统里存在哪些用户名</b>。
        // 这条断言把这个安全决策固化下来，防止后续有人"为了提示更友好"而改回去。
        assertThat(unknownUser.getCode()).isEqualTo(wrongPassword.getCode());
        assertThat(wrongPassword.getCode()).isEqualTo(CommonErrorCode.BAD_CREDENTIALS.code());
    }

    @Test
    @DisplayName("用户不存在时不累加任何计数（避免被用于制造无效写入）")
    void should_not_count_failure_for_unknown_username() {
        catchThrowable(() -> login("no-such-user-at-all", WRONG_PASSWORD));

        // 不存在的用户没有行可更新，但更重要的原因是：
        // 对不存在的用户名计数等于给攻击者一个"无限制造数据库写入"的入口
        assertThat(reloadAdmin().failCount()).isZero();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private LoginResult login(String username, String password) {
        return authAppService.login(new LoginCommand(null, username, password, "127.0.0.1"));
    }

    /** 重新从数据库加载超管（**新的事务**，用于验证写入是否真的提交）。 */
    private User reloadAdmin() {
        return userRepository.findByUsername(Username.of(ADMIN_USERNAME))
                .orElseThrow(() -> new AssertionError("超管账号不存在，AdminUserInitializer 可能未执行"));
    }
}
