package com.webadmin.dispatch;

import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单状态机（看板拖拽的后端语义）的端到端集成测试。
 *
 * <h3>被验证的状态机</h3>
 * <pre>
 *   PENDING --claim--&gt; CLAIMED --complete--&gt; DONE（终态）
 *      ^                   |
 *      +------release------+     PENDING --超时--&gt; ESCALATED --claim--&gt; CLAIMED
 * </pre>
 *
 * <h3>为什么每个迁移都值得一条 HTTP 级断言</h3>
 * 所有迁移都是<b>条件 UPDATE</b>（WHERE 带前置状态与身份）——
 * 它们的正确性依赖数据库行锁下的真实行为，mock 或 service 层测试都测不出
 * "并发下 affected 到底是不是 1"。看板前端把乐观更新建立在这些语义上，
 * 后端语义错一个，前端的回滚就会静默吞掉用户的操作。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class OpsTicketIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String ADMIN_PASSWORD = "Test@123456";
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    /** 业务码 OPERATION_NOT_ALLOWED（CommonErrorCode）。 */
    private static final int CODE_NOT_ALLOWED = 10006;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    private String adminToken;

    @BeforeEach
    void prepare() throws Exception {
        TenantContext.set(TENANT_ID);
        Map<String, Object> json = readJson(restClient.post().uri(url("/api/v1/auth/login"))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantCode", "main", "username", "admin",
                        "password", ADMIN_PASSWORD))
                .retrieve());
        adminToken = text(data(json), "accessToken");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    @DisplayName("状态机主链：claim → release → claim → complete；终态与越权全部被拒")
    void stateMachineHappyPathAndGuards() throws Exception {
        long id = insertTicket("IT 主链 " + RUN, 60, null);

        // claim：PENDING → CLAIMED
        assertCodeOk(post("/api/v1/dispatch/tickets/" + id + "/claim", adminToken, null), "抢单");
        assertThat(stateOf(id)).isEqualTo("CLAIMED");

        // release：CLAIMED → PENDING，且接单人被清空
        assertCodeOk(post("/api/v1/dispatch/tickets/" + id + "/release", adminToken, null), "放回");
        assertThat(stateOf(id)).isEqualTo("PENDING");

        // 未接单人办结 → 明确拒绝（不是 500，是业务码）
        Map<String, Object> denied = postAllowingFailure(
                "/api/v1/dispatch/tickets/" + id + "/complete", adminToken, null);
        assertThat(code(denied)).as("PENDING 状态不得办结").isEqualTo(CODE_NOT_ALLOWED);

        // 放回后重新抢到（原子性对同一用户同样成立）
        assertCodeOk(post("/api/v1/dispatch/tickets/" + id + "/claim", adminToken, null), "再抢");
        assertCodeOk(post("/api/v1/dispatch/tickets/" + id + "/complete", adminToken, null), "办结");
        assertThat(stateOf(id)).isEqualTo("DONE");

        // DONE 是终态：重复办结与再次抢单都必须被拒
        Map<String, Object> again = postAllowingFailure(
                "/api/v1/dispatch/tickets/" + id + "/complete", adminToken, null);
        assertThat(code(again)).as("终态不得重复办结").isEqualTo(CODE_NOT_ALLOWED);

        Map<String, Object> reClaim = postAllowingFailure(
                "/api/v1/dispatch/tickets/" + id + "/claim", adminToken, null);
        assertThat(code(reClaim)).as("终态不得再被抢单").isEqualTo(CODE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("SLA 超时升级为 ESCALATED 后仍可被接手（升级是催办不是作废）")
    void escalatedTicketIsStillClaimable() throws Exception {
        // created_at 越过 SLA 期限 → list 的惰性升级会把它推进 ESCALATED
        long id = insertTicket("IT 升级单 " + RUN, 1, java.time.LocalDateTime.now().minusMinutes(5));

        Map<String, Object> json = get("/api/v1/dispatch/tickets");
        assertCodeOk(json, "列表（触发惰性升级）");
        assertThat(stateOf(id))
                .as("过期工单必须已被惰性升级（否则 SLA 防线形同虚设）")
                .isEqualTo("ESCALATED");

        assertCodeOk(post("/api/v1/dispatch/tickets/" + id + "/claim", adminToken, null),
                "升级单可被接手");
        assertThat(stateOf(id)).isEqualTo("CLAIMED");
    }

    // ==================================================================
    // 数据与 HTTP 助手
    // ==================================================================

    /** 插入一条测试工单，返回 id。主键非自增（应用侧雪花 ID），测试显式给值。 */
    private long insertTicket(String title, int slaMinutes,
                              java.time.LocalDateTime createdAt) {
        long id = 910_000_000L + (System.nanoTime() % 90_000_000L);
        jdbc.update("INSERT INTO ops_ticket (id, tenant_id, title, channel, priority, state, "
                        + "claimer, sla_minutes, created_at, update_time) "
                        + "VALUES (?, ?, ?, 'web', 'HIGH', 'PENDING', NULL, ?, ?, NOW(3))",
                id, TENANT_ID, title, slaMinutes,
                createdAt == null ? java.time.LocalDateTime.now() : createdAt);
        return id;
    }

    private String stateOf(long id) {
        return jdbc.queryForObject("SELECT state FROM ops_ticket WHERE id = ?", String.class, id);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> get(String path) {
        return readJson(restClient.get().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .headers(h -> h.setBearerAuth(adminToken))
                .retrieve());
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
                .body(body == null ? Map.of() : body)
                .retrieve());
    }

    private Map<String, Object> postAllowingFailure(String path, String token, Object body) {
        try {
            return post(path, token, body);
        } catch (RestClientResponseException ex) {
            String raw = ex.getResponseBodyAsString();
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception parseError) {
                return Map.of();
            }
        }
    }

    private Map<String, Object> readJson(
            org.springframework.web.client.RestClient.ResponseSpec spec) {
        Map<String, Object> body = spec.body(JSON_MAP);
        return body == null ? Map.of() : body;
    }

    private static Map<String, Object> data(Map<String, Object> json) {
        Object data = json.get("data");
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
    }

    private static void assertCodeOk(Map<String, Object> json, String what) {
        Object code = json.get("code");
        assertThat(code).as(what + " 的响应码").isEqualTo(0);
    }

    private static int code(Map<String, Object> json) {
        Object code = json.get("code");
        return code instanceof Number ? ((Number) code).intValue() : -1;
    }

    private static String text(Map<String, Object> json, String field) {
        Object value = json.get(field);
        return value == null ? "" : String.valueOf(value);
    }
}
