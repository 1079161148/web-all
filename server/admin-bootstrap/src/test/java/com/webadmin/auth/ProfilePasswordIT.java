package com.webadmin.auth;

import com.webadmin.application.iam.UserAppService;
import com.webadmin.application.iam.command.CreateUserCommand;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 个人中心「修改密码」的端到端测试。
 *
 * <h3>⚠️ 为什么用<b>专用账号</b>而不是种子管理员</h3>
 * 本测试会<b>改变被测账号的密码</b>。第一版直接改种子管理员，
 * 结果：还原语句把密码改回 {@code Test@123456} 时被<b>密码策略拒绝</b>
 * （该值含弱片段 "123456"，初始化时能设进去，但走改密码接口必然被拦）——
 * 于是密码停留在测试值上，<b>同一 JVM 内后续所有测试类集体登录失败</b>
 * （21 个用例连挂）。这类"测试互相投毒"的排查成本极高，症状还极具误导性：
 * 失败的是别的、看起来毫不相关的测试。
 *
 * <p>由此得到两条规则，本类都遵守：
 * <ol>
 *   <li><b>不改共享夹具的状态</b>：需要"会被改变的东西"，就自己造一个</li>
 *   <li><b>造的账号要用完即删</b>（归还租户配额），否则反复跑测试会耗尽配额</li>
 * </ol>
 *
 * <h3>被验证的两条安全性质</h3>
 * <ol>
 *   <li><b>必须校验原密码</b>：只凭"已登录"就能改密，意味一次 XSS 或一次忘锁屏
 *       就能让攻击者把账号据为己有。这里断言"原密码错误 → 拒绝"，
 *       并且<b>原密码仍然可用</b> —— 只断言错误码不够，要断言副作用没发生</li>
 *   <li><b>改密后全部令牌立即失效</b>：这是改密的语义本身
 *       （"把别处的登录踢掉"），包括当前设备</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class ProfilePasswordIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String INITIAL_PASSWORD = "Init@2026#Ab";
    private static final String NEW_PASSWORD = "Temp@2026#Zq";

    /** 账号或密码错误（CommonErrorCode.BAD_CREDENTIALS）。 */
    private static final int CODE_BAD_CREDENTIALS = 20003;

    @LocalServerPort
    private int port;

    private final RestClient rest = RestClient.create();

    @Autowired
    private UserAppService userAppService;

    private String username;
    private long userId;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        // 每次用唯一账号：测试之间互不影响，也不需要"还原"（账号本身就是一次性的）
        username = "pwd" + (System.nanoTime() % 1_000_000);
        userId = userAppService.createUser(new CreateUserCommand(
                username, "改密测试账号", INITIAL_PASSWORD, null, null, null, 2, Set.of()));
    }

    @AfterEach
    void tearDown() {
        // 删除即归还配额（配额只扣不还的话，反复跑测试会把租户配额耗尽）
        try {
            userAppService.deleteUser(userId);
        } catch (RuntimeException ignored) {
            // 已被用例删除或删除失败都不影响结论：测试结果不依赖清理是否成功
        }
        TenantContext.clear();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> login(String password) {
        return rest.post().uri(url("/api/v1/auth/login"))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantCode", "main", "username", username, "password", password))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    @SuppressWarnings("unchecked")
    private static String accessToken(Map<String, Object> body) {
        Object data = body.get("data");
        if (data instanceof Map<?, ?> map) {
            Object token = ((Map<String, Object>) map).get("accessToken");
            return token == null ? null : String.valueOf(token);
        }
        return null;
    }

    private static int codeOf(Map<String, Object> body) {
        return body.get("code") instanceof Number number ? number.intValue() : -1;
    }

    private Map<String, Object> changePassword(String token, String oldPassword, String newPassword) {
        return rest.put().uri(url("/api/v1/auth/password"))
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("oldPassword", oldPassword, "newPassword", newPassword))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    /** 用访问令牌请求受保护接口，返回 HTTP 状态码（401 = 令牌已失效）。 */
    private int statusOfCurrentUser(String token) {
        return rest.get().uri(url("/api/v1/auth/me"))
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    @Test
    @DisplayName("原密码错误：拒绝修改，且密码没有发生变化")
    void rejectsWrongOldPassword() {
        String token = accessToken(login(INITIAL_PASSWORD));
        assertThat(token).as("初始密码应能登录").isNotBlank();

        Map<String, Object> result = changePassword(token, "Wrong@Old#Pwd", NEW_PASSWORD);
        assertThat(codeOf(result)).as("原密码错误必须被拒绝").isEqualTo(CODE_BAD_CREDENTIALS);

        // 关键：断言副作用没有发生 —— 若实现"先改后校验"，错误码同样正确
        // 但密码已经被换掉了，那是一个极其危险且不易察觉的 bug
        assertThat(accessToken(login(INITIAL_PASSWORD))).as("原密码必须仍然可用").isNotBlank();
        assertThat(codeOf(login(NEW_PASSWORD))).as("新密码不得生效").isEqualTo(CODE_BAD_CREDENTIALS);
    }

    @Test
    @DisplayName("修改成功：旧令牌立即失效（需重新登录），新密码可登录")
    void changesPasswordAndRevokesAllTokens() {
        String oldToken = accessToken(login(INITIAL_PASSWORD));
        assertThat(oldToken).isNotBlank();
        assertThat(statusOfCurrentUser(oldToken)).as("改密前令牌可用").isEqualTo(200);

        Map<String, Object> result = changePassword(oldToken, INITIAL_PASSWORD, NEW_PASSWORD);
        assertThat(codeOf(result)).as("改密应成功").isZero();

        // ① 旧令牌立即失效：令牌版本号被提升（持久生效，不依赖 Redis 存活）
        assertThat(statusOfCurrentUser(oldToken)).as("改密后旧访问令牌必须失效").isEqualTo(401);

        // ② 新密码可用、旧密码作废
        assertThat(accessToken(login(NEW_PASSWORD))).as("新密码应能登录").isNotBlank();
        assertThat(codeOf(login(INITIAL_PASSWORD))).as("旧密码应作废").isEqualTo(CODE_BAD_CREDENTIALS);
    }
}
