package com.webadmin.auth;

import com.webadmin.application.iam.AuthAppService;
import com.webadmin.application.iam.RegisterAppService;
import com.webadmin.application.iam.command.LoginCommand;
import com.webadmin.application.iam.command.RegisterCommand;
import com.webadmin.application.iam.dto.LoginResult;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自助注册的集成测试。
 *
 * <h3>本功能最容易被做错的两点，也是本测试的重点</h3>
 * <ol>
 *   <li><b>注册不能顺带给权限</b>：若新账号被默认赋予某个角色，
 *       那么"任何人打开注册页"就等于"任何人可以给自己发权限"。
 *       这里断言注册后的账号<b>权限集与角色集都为空</b> ——
 *       注意"能登录"与"有权限"是两件事，本测试把两者分开断言：
 *       用户能登录（状态正常），但什么都看不到（没有授权）</li>
 *   <li><b>不能绕过既有的建号规则</b>：用户名唯一性、密码强度、租户配额
 *       都由 {@code UserAppService.createUser} 保证，注册入口只是收窄字段。
 *       这里通过"重名注册被拒"来证明复用是真的（而不是自己写了一套宽松校验）</li>
 * </ol>
 *
 * <p>验证码语义在 {@code CaptchaLoginIT} 中验证（与本类同样用反射开关），
 * 本类默认处于"验证码关闭"的测试基座下，专注注册本身的业务规则。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class RegisterIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String PASSWORD = "Reg@2026#Xy";

    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    @Autowired
    private RegisterAppService registerAppService;

    @Autowired
    private AuthAppService authAppService;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(registerAppService, "registerEnabled", true);
        TenantContext.clear();
    }

    private RegisterCommand command(String username) {
        return new RegisterCommand(username, null, PASSWORD, null, null, null);
    }

    @Test
    @DisplayName("注册成功：账号可用，但**没有任何权限**（授权只能由管理员做）")
    void registersWithoutAnyPermission() {
        String username = "reg" + RUN + "a";

        long userId = registerAppService.register(command(username));
        assertThat(userId).isPositive();

        // 能登录 —— 注册出来的账号状态正常（不是"待审批"的僵尸号）
        LoginResult result = authAppService.login(
                new LoginCommand(null, username, PASSWORD, "127.0.0.1", null, null, null));
        assertThat(result.accessToken()).isNotBlank();

        // 但什么都没有：权限与角色皆空 → 前端菜单为空，管理员授权后才可用。
        // 这两条断言是本功能的安全边界，删掉它们等于删掉整个设计
        assertThat(result.permissions()).isEmpty();
        assertThat(result.roles()).isEmpty();
    }

    @Test
    @DisplayName("用户名重复：走既有唯一性校验（不是自己实现的宽松版本）")
    void rejectsDuplicatedUsername() {
        String username = "reg" + RUN + "b";
        registerAppService.register(command(username));

        assertThatThrownBy(() -> registerAppService.register(command(username)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("密码过弱：注册不能绕过平台的密码强度策略")
    void rejectsWeakPassword() {
        RegisterCommand weak =
                new RegisterCommand("reg" + RUN + "c", null, "123", null, null, null);

        assertThatThrownBy(() -> registerAppService.register(weak))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("注册开关关闭：明确拒绝（而不是装作接口不存在）")
    void rejectsWhenRegisterDisabled() {
        ReflectionTestUtils.setField(registerAppService, "registerEnabled", false);

        assertThatThrownBy(() -> registerAppService.register(command("reg" + RUN + "d")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("注册功能已关闭");
    }
}
