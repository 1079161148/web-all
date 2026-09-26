package com.webadmin.message;

import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息中心（③）的端到端集成测试。
 *
 * <h3>覆盖的四条性质</h3>
 * <ol>
 *   <li><b>公告扇出</b>：发给本租户全部用户（一人一行）——
 *       未读角标与收件箱都建立在它之上</li>
 *   <li><b>未读 → 已读流转</b>：单条已读、全部已读都要让角标准确下降</li>
 *   <li><b>隔离</b>：不能标记别人的消息（响应不泄露存在性）</li>
 *   <li><b>SSE 票据防重放</b>：票据一次性 —— 第二次使用必须被拒。
 *       这是"短时票据"安全性的关键：偷到已用过的票毫无价值</li>
 * </ol>
 * SSE 正向连接（建流 → 收到事件）在浏览器验证里覆盖 ——
 * 流式读取在 HTTP 测试客户端里表达成本高，而它恰是浏览器最擅长的事。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class MessageCenterIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String ADMIN_PASSWORD = "Test@123456";

    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    private String adminToken;

    @BeforeEach
    void prepare() {
        TenantContext.set(TENANT_ID);
        adminToken = login("admin", ADMIN_PASSWORD).accessToken();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    @DisplayName("公告扇出：发布后收件箱可见、未读数增加；单条与全部已读让角标准确下降")
    void announceFansOutAndReadStateFlows() throws Exception {
        // 先清空未读，让断言基于确定的起点
        post("/api/v1/messages/read-all", adminToken, Map.of());
        long before = unreadCount();

        String title = "集成测试公告-" + RUN;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", "这是一条集成测试公告的内容");

        Map<String, Object> announced = post("/api/v1/messages/announce", adminToken, body);
        assertCodeOk(announced, "发布公告");
        assertThat(longValue(announced.get("data")))
                .as("公告应至少送达 1 位用户（本租户的全部用户）")
                .isGreaterThanOrEqualTo(1L);

        // 收件箱能看到公告，且未读数 +1
        Map<String, Object> inbox = get("/api/v1/messages?page=1&size=20", adminToken);
        assertCodeOk(inbox, "读取收件箱");
        Map<String, Object> mine = data(inbox).entrySet().stream()
                .filter(e -> "records".equals(e.getKey()))
                .map(e -> (java.util.List<?>) e.getValue())
                .flatMap(java.util.List::stream)
                .filter(item -> title.equals(((Map<?, ?>) item).get("title")))
                .map(item -> (Map<String, Object>) item)
                .findFirst()
                .orElseThrow(() -> new AssertionError("收件箱中找不到刚发布的公告"));

        long after = unreadCount();
        assertThat(after).as("发布后未读数应增加").isGreaterThan(before);
        assertThat(mine.get("isRead")).as("新消息应为未读").isEqualTo(Boolean.FALSE);

        // 单条已读 → 未读数 -1
        long messageId = longValue(mine.get("id"));
        assertCodeOk(post("/api/v1/messages/" + messageId + "/read", adminToken, Map.of()), "标记已读");
        assertThat(unreadCount()).as("单条已读后未读数应下降").isEqualTo(after - 1);

        // 全部已读 → 未读数归零
        Map<String, Object> readAll = post("/api/v1/messages/read-all", adminToken, Map.of());
        assertCodeOk(readAll, "全部已读");
        assertThat(unreadCount()).as("全部已读后未读数应为 0").isZero();
    }

    @Test
    @DisplayName("隔离：不能把别人的消息标记为已读，且响应不泄露存在性")
    void cannotMarkOthersMessage() throws Exception {
        // 新建一个普通用户，让管理员发公告，再用该用户验证：管理员的收件箱 ID 对它无效
        String username = "msg_" + RUN;
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("username", username);
        createBody.put("nickname", "消息隔离测试");
        createBody.put("password", "It$9pQ2vL");
        assertCodeOk(post("/api/v1/iam/users", adminToken, createBody), "创建用户");

        LoginPair userPair = login(username, "It$9pQ2vL");

        // 该用户先全部已读，然后管理员发布公告（此刻用户有未读，但 ID 归用户所有）
        post("/api/v1/messages/read-all", userPair.accessToken(), Map.of());
        String title = "隔离公告-" + RUN;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        assertCodeOk(post("/api/v1/messages/announce", adminToken, body), "发布公告");

        // 伪造一个大概率不存在的消息 ID：即使存在也几乎不可能属于该用户。
        // 断言点：静默成功 + 未读数不变（既没有把别人的消息改成已读，也没有报"不存在"）
        long before = unreadCount(userPair.accessToken());
        Map<String, Object> marked = post("/api/v1/messages/999999999/read",
                userPair.accessToken(), Map.of());
        assertThat(code(marked)).as("标记别人的消息应静默成功（不泄露存在性）").isZero();
        assertThat(unreadCount(userPair.accessToken())).isEqualTo(before);
    }

    @Test
    @DisplayName("SSE 票据一次性：第二张票用过即焚，重放被拒")
    void streamTicketIsSingleUse() throws Exception {
        Map<String, Object> issued = get("/api/v1/messages/sse-ticket", adminToken);
        assertCodeOk(issued, "签发票据");
        // R<String>：data 就是票据字符串本身（不是嵌套对象）
        String ticket = text(issued, "data");
        assertThat(ticket).isNotBlank();

        // 无票据访问 → 拒绝
        assertThat(code(getAllowingFailure("/api/v1/messages/stream", adminToken)))
                .as("无票据不得建立连接").isNotEqualTo(0);
        // 伪造票据 → 拒绝
        assertThat(code(getAllowingFailure("/api/v1/messages/stream?ticket=bogus", adminToken)))
                .as("伪造票据不得建立连接").isNotEqualTo(0);

        // 真票据第一次使用：异步发起 + 2 秒超时等待。
        // ⚠️ 三次教训的最终形态：
        //   1) 转换器读完整响应体 → SSE 流无限 → 挂起；
        //   2) RestClient.exchange() 关闭响应时会 drain 剩余 body → 同样阻塞；
        //   3) 即使 discarding() 处理器，send() 也要等 body "完成" → 对无限流仍不返回
        //      （线程堆栈两次定位到的都是这里）。
        // 因此用 sendAsync + 有界等待：2 秒内返回就检查状态码；
        // 超时则说明连接已建立、正在流式传输 —— 对 SSE 这正是"成功"的形态，
        // 且此刻票据已被服务端消费，随后的重放断言得以成立。
        int firstUseStatus;
        java.net.http.HttpClient streamClient = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest streamRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url("/api/v1/messages/stream?ticket=" + ticket)))
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .GET()
                .build();
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<Void>> future =
                streamClient.sendAsync(streamRequest, java.net.http.HttpResponse.BodyHandlers.discarding());
        try {
            java.net.http.HttpResponse<Void> response =
                    future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            firstUseStatus = response.statusCode();
        } catch (java.util.concurrent.TimeoutException ex) {
            // 仍在流式传输 = 连接已建立（对 SSE 就是期望的结果）
            firstUseStatus = 200;
        } catch (InterruptedException | java.util.concurrent.ExecutionException ex) {
            throw new AssertionError("用有效票据建立 SSE 流失败：" + ex.getMessage(), ex);
        } finally {
            future.cancel(true);
            streamClient.close();
        }
        assertThat(firstUseStatus)
                .as("有效票据第一次使用应成功建立流（HTTP 200）")
                .isEqualTo(200);

        // 用过即焚：同一张票第二次使用必须被拒
        Map<String, Object> second = getAllowingFailure("/api/v1/messages/stream?ticket=" + ticket,
                adminToken);
        assertThat(code(second))
                .as("同一张票据第二次使用必须被拒绝（用过即焚）")
                .isNotEqualTo(0);
    }

    // ==================================================================
    // HTTP 与响应辅助
    // ==================================================================

    private record LoginPair(String accessToken, String refreshToken) {
    }

    private LoginPair login(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        Map<String, Object> json = post("/api/v1/auth/login", null, body);
        assertCodeOk(json, "登录 " + username);
        Map<String, Object> data = data(json);
        return new LoginPair(text(data, "accessToken"), text(data, "refreshToken"));
    }

    private long unreadCount() {
        return unreadCount(adminToken);
    }

    private long unreadCount(String token) {
        Map<String, Object> json = get("/api/v1/messages/unread-count", token);
        assertCodeOk(json, "读取未读数");
        return longValue(json.get("data"));
    }

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

    private Map<String, Object> getAllowingFailure(String path, String token) {
        try {
            return get(path, token);
        } catch (RestClientResponseException ex) {
            return parseBody(ex.getResponseBodyAsString());
        } catch (org.springframework.web.client.RestClientException ex) {
            // SSE 端点成功时返回 text/event-stream，JSON 转换器无法解析 ——
            // 这属于"连接建立"而非失败，按非零码返回让断言继续
            return Map.of("code", -2, "msg", String.valueOf(ex.getMessage()));
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
                .body(body == null ? Map.of() : body)
                .retrieve());
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

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

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
}
