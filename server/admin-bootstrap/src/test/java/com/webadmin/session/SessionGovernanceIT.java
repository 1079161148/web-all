package com.webadmin.session;

import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话治理（②）的端到端集成测试。
 *
 * <h3>被验证的是四条安全性质，每条对应一类真实风险</h3>
 * <ol>
 *   <li><b>登录建立会话</b>：签发访问+刷新令牌对，且出现在在线列表中 ——
 *       没有它，后面所有"吊销"都无从谈起</li>
 *   <li><b>轮换 + 重放检测</b>：刷新令牌用一次即作废；
 *       旧令牌被再次使用 = 出现两个持有者 → 整族吊销 + 强制下线。
 *       这是"令牌被窃取后攻击者与合法用户竞争使用"场景的兜底</li>
 *   <li><b>注销立即生效</b>：退出登录后访问令牌与刷新令牌同时作废 ——
 *       只删访问令牌、留着刷新令牌的"退出"形同虚设</li>
 *   <li><b>改密码吊销全部令牌</b>：版本号提升后，该用户已签发的所有令牌立即失效 ——
 *       这是"凭证可能已泄露"场景的标准处置</li>
 * </ol>
 *
 * <p>与 {@code DataScopeIT} 同样的约定：容器与数据在整个 JVM 内共享，
 * 用户名带本次运行后缀，断言写成相对事实。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class SessionGovernanceIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String ADMIN_PASSWORD = "Test@123456";
    private static final String USER_PASSWORD = "It$9pQ2vL";
    private static final String RESET_PASSWORD = "Nw$7xQ4mP";

    /** 刷新令牌重放的业务码（IamErrorCode.REFRESH_TOKEN_REUSED）。 */
    private static final int CODE_REFRESH_REUSED = 31007;

    /** 刷新令牌 Cookie 名（与 AuthController 一致）。 */
    private static final String REFRESH_COOKIE = "refresh_token";

    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    private String adminToken;
    private String adminRefreshToken;

    @BeforeEach
    void prepare() throws Exception {
        TenantContext.set(TENANT_ID);
        LoginPair pair = login("admin", ADMIN_PASSWORD);
        adminToken = pair.accessToken();
        adminRefreshToken = pair.refreshToken();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    @DisplayName("登录签发令牌对，且当前会话出现在在线列表并被标记")
    void loginIssuesTokenPairAndSession() throws Exception {
        assertThat(adminToken).as("访问令牌").isNotBlank();
        assertThat(adminRefreshToken).as("刷新令牌").isNotBlank();

        Map<String, Object> json = get("/api/v1/auth/sessions", adminToken);
        assertCodeOk(json, "读取在线会话");

        List<Map<String, Object>> sessions = listData(json);
        assertThat(sessions).as("登录后在线列表不应为空").isNotEmpty();
        assertThat(sessions.stream().filter(s -> Boolean.TRUE.equals(s.get("current"))).count())
                .as("在线列表必须恰好标记一个当前会话")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("刷新轮换：旧令牌一次有效；再次使用即重放 → 整族吊销并强制下线")
    void refreshRotationDetectsReplay() throws Exception {
        // 第一次刷新（Cookie 通道 —— 浏览器的真实形态）：成功并轮换出新令牌
        RefreshResult first = refreshWithCookie(adminRefreshToken);
        assertCodeOk(first.body(), "第一次刷新");
        String newAccess = text(data(first.body()), "accessToken");
        assertThat(newAccess).isNotBlank();
        assertThat(first.newRefreshToken())
                .as("轮换必须下发新的刷新令牌（经 Set-Cookie 下发，响应体不再携带 ——"
                        + "否则 XSS 仍可读到这张长期凭证）")
                .isNotBlank()
                .isNotEqualTo(adminRefreshToken);

        // 旧刷新令牌经请求体通道（非浏览器客户端的形态）重放 = 同样识别为重放：
        // 重放检测必须与令牌的传递通道无关
        Map<String, Object> replay = postAllowingFailure("/api/v1/auth/refresh", null,
                Map.of("refreshToken", adminRefreshToken));
        assertThat(code(replay))
                .as("旧刷新令牌第二次使用必须被识别为重放并拒绝")
                .isEqualTo(CODE_REFRESH_REUSED);

        // 重放保护必须连带吊销整个族：轮换出的新访问令牌也已失效
        Map<String, Object> me = getAllowingFailure("/api/v1/auth/me", newAccess);
        assertThat(code(me))
                .as("重放后同族会话必须被强制下线（否则攻击者仍可用轮换出的令牌）")
                .isNotEqualTo(0);
    }

    @Test
    @DisplayName("注销立即生效：访问令牌与刷新令牌同时作废")
    void logoutRevokesBothTokensImmediately() throws Exception {
        assertCodeOk(post("/api/v1/auth/logout", adminToken, Map.of()), "退出登录");

        Map<String, Object> me = getAllowingFailure("/api/v1/auth/me", adminToken);
        assertThat(code(me))
                .as("注销后访问令牌必须立即失效")
                .isNotEqualTo(0);

        Map<String, Object> refresh = refreshWithCookie(adminRefreshToken).body();
        assertThat(code(refresh))
                .as("注销后刷新令牌必须同时作废（否则可换出新令牌绕过注销）")
                .isNotEqualTo(0);
    }

    @Test
    @DisplayName("重置密码吊销该用户全部令牌（版本号提升）")
    void passwordResetRevokesAllTokens() throws Exception {
        String username = name("revoke_me");
        createUser(adminToken, username, USER_PASSWORD);
        LoginPair pair = login(username, USER_PASSWORD);

        // 重置密码（管理员操作）
        long userId = findUserId(username);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", RESET_PASSWORD);
        assertCodeOk(put("/api/v1/iam/users/" + userId + "/password", adminToken, body), "重置密码");

        Map<String, Object> me = getAllowingFailure("/api/v1/auth/me", pair.accessToken());
        assertThat(code(me))
                .as("改密后旧访问令牌必须立即失效（持久生效，不依赖令牌自然过期）")
                .isNotEqualTo(0);

        Map<String, Object> refresh = refreshWithCookie(pair.refreshToken()).body();
        assertThat(code(refresh))
                .as("改密后刷新令牌必须同时作废")
                .isNotEqualTo(0);

        // 新密码可以正常登录（吊销不能把账号本身弄坏）
        LoginPair relogin = login(username, RESET_PASSWORD);
        assertThat(relogin.accessToken()).isNotBlank();
    }

    // ==================================================================
    // 数据准备与 HTTP 辅助
    // ==================================================================

    private record LoginPair(String accessToken, String refreshToken) {
    }

    private LoginPair login(String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);

        ResponseEntity<Map<String, Object>> entity = restClient.post()
                .uri(url("/api/v1/auth/login"))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(JSON_MAP);
        Map<String, Object> json = orEmpty(entity.getBody());
        assertCodeOk(json, "登录 " + username);
        // ⚠️ 刷新令牌从 Set-Cookie 取（HttpOnly 只对浏览器脚本生效，测试客户端读得到）：
        // 响应体已不再携带它 —— 这正是要验证的契约
        return new LoginPair(text(data(json), "accessToken"),
                refreshCookieValue(entity.getHeaders()));
    }

    /** 刷新令牌轮换结果：JSON 响应 + 经 Set-Cookie 轮换出的新刷新令牌。 */
    private record RefreshResult(Map<String, Object> body, String newRefreshToken) {
    }

    /** 用 Cookie 通道刷新（浏览器的真实形态）。失败不抛：body 的 code 字段承载结果。 */
    private RefreshResult refreshWithCookie(String refreshToken) {
        try {
            ResponseEntity<Map<String, Object>> entity = restClient.post()
                    .uri(url("/api/v1/auth/refresh"))
                    .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                    .header(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + refreshToken)
                    .retrieve()
                    .toEntity(JSON_MAP);
            return new RefreshResult(orEmpty(entity.getBody()),
                    refreshCookieValue(entity.getHeaders()));
        } catch (RestClientResponseException ex) {
            return new RefreshResult(parseBody(ex.getResponseBodyAsString()), "");
        }
    }

    /** 从 Set-Cookie 头里解析 refresh_token 的值。 */
    private static String refreshCookieValue(HttpHeaders headers) {
        for (String value : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
            if (value.startsWith(REFRESH_COOKIE + "=")) {
                String cookie = value.substring((REFRESH_COOKIE + "=").length());
                int end = cookie.indexOf(';');
                return end < 0 ? cookie : cookie.substring(0, end);
            }
        }
        return "";
    }

    private static Map<String, Object> orEmpty(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private void createUser(String token, String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("nickname", "集成测试-" + username);
        body.put("password", password);
        body.put("sex", 2);

        assertCodeOk(post("/api/v1/iam/users", token, body), "创建用户 " + username);
    }

    private long findUserId(String username) throws Exception {
        Map<String, Object> json = get("/api/v1/iam/users?page=1&size=200", adminToken);
        for (Map<String, Object> node : records(data(json))) {
            if (username.equals(node.get("username"))) {
                return longValue(node.get("id"));
            }
        }
        throw new AssertionError("测试用户不存在：" + username);
    }

    private static String name(String base) {
        return "sg_" + base + "_" + RUN;
    }

    // ---- HTTP ----

    private Map<String, Object> get(String path, String token) {
        return readJson(restClient.get().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .retrieve());
    }

    /** 与 {@link #get} 相同，但 4xx/5xx 不抛异常 —— 会话治理的核心断言就是"被拒"。 */
    private Map<String, Object> getAllowingFailure(String path, String token) {
        try {
            return get(path, token);
        } catch (RestClientResponseException ex) {
            return parseBody(ex.getResponseBodyAsString());
        }
    }

    private Map<String, Object> post(String path, String token, Object body) {
        return readJson(restClient.post().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve());
    }

    private Map<String, Object> postAllowingFailure(String path, String token, Object body) {
        try {
            return post(path, token, body);
        } catch (RestClientResponseException ex) {
            return parseBody(ex.getResponseBodyAsString());
        }
    }

    private Map<String, Object> put(String path, String token, Object body) {
        return readJson(restClient.put().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve());
    }

    private Map<String, Object> readJson(
            org.springframework.web.client.RestClient.ResponseSpec spec) {
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

    private String url(String path) {
        return "http://localhost:" + port + path;
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
    private static List<Map<String, Object>> listData(Map<String, Object> response) {
        Object value = response.get("data");
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> data) {
        Object value = data.get("records");
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }
}
