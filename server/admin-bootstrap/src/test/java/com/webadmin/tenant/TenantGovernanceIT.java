package com.webadmin.tenant;

import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户治理（④）的端到端集成测试。
 *
 * <h3>三条性质，各对应一个"必须真实工作"的环节</h3>
 * <ol>
 *   <li><b>开通即可用</b>：一键开通返回的管理员必须能立刻登录并使用系统 ——
 *       "开通了一个没人进得去的租户"是该功能最典型的失败形态</li>
 *   <li><b>配额在执行点拦截</b>：套餐用户数用完后创建被拒（业务码 30006），
 *       且删除用户会归还名额 —— 只在配置里写着、从不生效的配额等于没有</li>
 *   <li><b>停用租户全员强制下线</b>：暂停后既有令牌失效、重新登录被拒、
 *       重新激活后可恢复 —— 这是租户治理的"牙齿"</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class TenantGovernanceIT extends AbstractIntegrationTest {

    /** 平台管理员所在租户（种子租户）。 */
    private static final long PLATFORM_TENANT_ID = 1L;
    private static final String ADMIN_PASSWORD = "Test@123456";

    /** 免费版配额：10 个用户（与 InMemorySubscriptionPlanRegistry 保持一致 —— 它是唯一事实来源）。 */
    private static final long FREE_PLAN_MAX_USERS = 10L;

    /** 租户暂停事件 → 强制下线是异步副作用（事务性 Outbox），等待其生效的上限。 */
    private static final Duration KICK_TIMEOUT = Duration.ofSeconds(10);

    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    private String adminToken;

    @Autowired
    private com.webadmin.domain.iam.repository.TenantRepository tenantRepository;

    @BeforeEach
    void prepare() throws Exception {
        TenantContext.set(PLATFORM_TENANT_ID);
        adminToken = login("admin", ADMIN_PASSWORD, PLATFORM_TENANT_ID).accessToken();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    @DisplayName("一键开通：返回的管理员可立即登录，用量看板可见（开通即可用）")
    void provisionedTenantIsImmediatelyUsable() throws Exception {
        String code = "prov-" + RUN;
        Map<String, Object> provisioned = provision(code, "PRO");

        long newTenantId = longValue(provisioned.get("tenantId"));
        String adminUsername = text(provisioned, "adminUsername");
        String initialPassword = text(provisioned, "initialPassword");

        // ① 开通返回的管理员能登录（用新租户的 ID 作为登录租户）
        LoginPair pair = login(adminUsername, initialPassword, newTenantId);
        assertThat(pair.accessToken()).isNotBlank();

        // ② 且能访问业务接口（角色/菜单装配完整，而不是"登录了但什么都做不了"）
        Map<String, Object> me = get("/api/v1/auth/me", pair.accessToken(), newTenantId);
        assertCodeOk(me, "新租户管理员读取自身信息");

        // ③ 用量看板：实时用户数应为 1（管理员自己），套餐为 PRO
        Map<String, Object> usage = get("/api/v1/iam/tenants/" + newTenantId + "/usage",
                adminToken, PLATFORM_TENANT_ID);
        assertCodeOk(usage, "读取用量");
        Map<String, Object> data = data(usage);
        assertThat(longValue(data.get("liveUsers"))).isEqualTo(1L);
        assertThat(text(data, "planCode")).isEqualTo("PRO");
        assertThat(text(data, "effectiveStatus")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("配额在执行点拦截：用户数达上限后创建被拒；删除用户归还名额")
    void quotaIsEnforcedAtExecutionPoint() throws Exception {
        String code = "quota-" + RUN;
        Map<String, Object> provisioned = provision(code, "FREE");
        long newTenantId = longValue(provisioned.get("tenantId"));
        String adminTokenOfNewTenant = login(text(provisioned, "adminUsername"),
                text(provisioned, "initialPassword"), newTenantId).accessToken();

        // 免费版 10 个用户，开通已用掉 1（管理员）→ 还能建 9 个
        for (int i = 1; i <= FREE_PLAN_MAX_USERS - 1; i++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", "u" + i + "_" + RUN);
            body.put("nickname", "配额测试-" + i);
            body.put("password", "It$9pQ2vL");
            assertCodeOk(post("/api/v1/iam/users", adminTokenOfNewTenant, body, newTenantId),
                    "创建第 " + i + " 个用户");
        }

        // 超出配额：明确拒绝（业务码 30006 TENANT_QUOTA_EXCEEDED），而不是 500 或静默成功
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "over_" + RUN);
        body.put("nickname", "超出配额");
        body.put("password", "It$9pQ2vL");
        Map<String, Object> rejected = postAllowingFailure("/api/v1/iam/users",
                adminTokenOfNewTenant, body, newTenantId);
        assertThat(code(rejected))
                .as("配额用尽后创建用户必须被拒绝（业务码 30006），实际：%s", rejected.get("msg"))
                .isEqualTo(30006);

        // 删除一个用户 → 名额归还 → 再次创建成功。
        // （只有扣减没有归还的配额是单向消耗，表现为"删了人还是建不了"）
        // ⚠️ 删除目标必须是普通用户：管理员受"禁止删除超管"保护
        String deletableUsername = "u" + (FREE_PLAN_MAX_USERS - 1) + "_" + RUN;
        Map<String, Object> users = get("/api/v1/iam/users?page=1&size=100",
                adminTokenOfNewTenant, newTenantId);
        long deletable = records(data(users)).stream()
                .filter(node -> deletableUsername.equals(node.get("username")))
                .map(node -> longValue(node.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到待删除的测试用户：" + deletableUsername));
        assertCodeOk(delete("/api/v1/iam/users/" + deletable, adminTokenOfNewTenant, newTenantId),
                "删除用户以释放名额");

        assertCodeOk(post("/api/v1/iam/users", adminTokenOfNewTenant, body, newTenantId),
                "释放名额后重新创建用户");
    }

    @Test
    @DisplayName("暂停租户：在线令牌被强制下线、重新登录被拒；重新激活后恢复")
    void suspendingTenantKicksAllSessions() throws Exception {
        String code = "kick-" + RUN;
        Map<String, Object> provisioned = provision(code, "PRO");
        long newTenantId = longValue(provisioned.get("tenantId"));
        String adminUsername = text(provisioned, "adminUsername");
        LoginPair pair = login(adminUsername, text(provisioned, "initialPassword"), newTenantId);

        // 平台管理员暂停该租户（事件 → 批量提升令牌版本号 → 全员下线）
        assertCodeOk(put("/api/v1/iam/tenants/" + newTenantId + "/suspend?reason=IT-test",
                adminToken, PLATFORM_TENANT_ID), "暂停租户");

        // 在线令牌被踢（异步副作用，轮询等待 Outbox 投递完成）
        waitFor(() -> code(getAllowingFailure("/api/v1/auth/me", pair.accessToken(), newTenantId)) != 0,
                KICK_TIMEOUT, "暂停后既有令牌应被强制下线");

        // 重新登录被拒（fail-closed：过期/停用租户在登录口就被拦下）
        Map<String, Object> relogin = postAllowingFailure("/api/v1/auth/login", null,
                loginBody(adminUsername, text(provisioned, "initialPassword")), newTenantId);
        assertThat(code(relogin))
                .as("暂停租户的管理员不应能重新登录")
                .isNotEqualTo(0);

        // 重新激活 → 可以再登录（冻结是可恢复的管理动作，不是终态）
        assertCodeOk(put("/api/v1/iam/tenants/" + newTenantId + "/activate",
                adminToken, PLATFORM_TENANT_ID), "重新激活租户");
        LoginPair restored = login(adminUsername, text(provisioned, "initialPassword"), newTenantId);
        assertThat(restored.accessToken()).isNotBlank();
    }

    // ==================================================================
    // 数据准备与 HTTP 辅助
    // ==================================================================

    private record LoginPair(String accessToken, String refreshToken) {
    }

    private Map<String, Object> provision(String code, String planCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "集成测试租户-" + code);
        body.put("planCode", planCode);
        body.put("adminUsername", "admin");

        Map<String, Object> json = post("/api/v1/iam/tenants/provision", adminToken, body,
                PLATFORM_TENANT_ID);
        assertCodeOk(json, "一键开通租户 " + code);
        return data(json);
    }

    private LoginPair login(String username, String password, long tenantId) {
        Map<String, Object> json = postAllowingFailure("/api/v1/auth/login", null,
                loginBody(username, password), tenantId);
        assertCodeOk(json, "登录 " + username + "（租户 " + tenantId + "）");
        Map<String, Object> data = data(json);
        return new LoginPair(text(data, "accessToken"), text(data, "refreshToken"));
    }

    private static Map<String, Object> loginBody(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    private void waitFor(java.util.function.BooleanSupplier condition,
                         Duration timeout, String description) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("等待超时：" + description);
    }

    // ---- HTTP（tenantId 显式传入：本用例跨多个租户发请求） ----

    private Map<String, Object> get(String path, String token, long tenantId) {
        return readJson(restClient.get().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(tenantId))
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .retrieve());
    }

    private Map<String, Object> getAllowingFailure(String path, String token, long tenantId) {
        try {
            return get(path, token, tenantId);
        } catch (RestClientResponseException ex) {
            return parseBody(ex.getResponseBodyAsString());
        }
    }

    private Map<String, Object> post(String path, String token, Object body, long tenantId) {
        return readJson(restClient.post().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(tenantId))
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve());
    }

    private Map<String, Object> postAllowingFailure(String path, String token, Object body, long tenantId) {
        try {
            return post(path, token, body, tenantId);
        } catch (RestClientResponseException ex) {
            return parseBody(ex.getResponseBodyAsString());
        }
    }

    private Map<String, Object> put(String path, String token, long tenantId) {
        return readJson(restClient.put().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(tenantId))
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .retrieve());
    }

    private Map<String, Object> delete(String path, String token, long tenantId) {
        return readJson(restClient.delete().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(tenantId))
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .retrieve());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> readJson(org.springframework.web.client.RestClient.ResponseSpec spec) {
        Map<String, Object> body = spec.body(JSON_MAP);
        return body == null ? Map.of() : body;
    }

    private static Map<String, Object> parseBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed =
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
            return parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    // ---- 响应读取 ----

    private static Map<String, Object> data(Map<String, Object> response) {
        Object value = response.get("data");
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static int code(Map<String, Object> response) {
        Object value = response.get("code");
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static String text(Map<String, Object> node, String key) {
        Object value = node.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static void assertCodeOk(Map<String, Object> response, String action) {
        assertThat(code(response))
                .as("%s 应成功，实际响应：%s", action, response.get("msg"))
                .isZero();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> data) {
        Object value = data.get("records");
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }
}
