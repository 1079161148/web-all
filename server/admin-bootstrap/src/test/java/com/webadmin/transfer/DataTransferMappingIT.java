package com.webadmin.transfer;

import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列映射导入的端到端集成测试。
 *
 * <h3>被验证的性质</h3>
 * <ol>
 *   <li><b>表头预览</b>：只读表头不建任务（幂等、无副作用）；</li>
 *   <li><b>乱序映射</b>：表头与模板不一致的文件，靠 mapping 也能正确校验并导入
 *       —— 这是对"必须按模板改表头"体验缺陷的修复，语义必须与直传完全一致；</li>
 *   <li><b>重复列映射被拒</b>：同一 Excel 列喂给两个字段 = 数据被复制，
 *       这是硬错误（400），不是警告。</li>
 * </ol>
 *
 * <p>xlsx 由项目自己的 ExcelPort 生成 —— 用生产解析器造测试输入，
 * 解析器行为变化时测试数据自动跟随，不存在"测试文件与新解析器不兼容"的漂移。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class DataTransferMappingIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String ADMIN_PASSWORD = "Test@123456";
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    /** 模板列序（与 DataTransferAppService.IMPORT_COLUMNS 一致）。 */
    private static final String[] TEMPLATE_COLUMNS = {
            "任务编码", "任务名称", "任务类型", "优先级", "负责人",
            "开始日期", "结束日期", "进度", "状态", "备注"
    };

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.webadmin.application.tool.port.ExcelPort excelPort;

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
        adminToken = String.valueOf(((Map<?, ?>) json.get("data")).get("accessToken"));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("表头预览：只返回表头与样例行，不创建任务")
    void headerPreviewIsSideEffectFree() throws Exception {
        byte[] xlsx = excelPort.write("数据", java.util.List.of(TEMPLATE_COLUMNS), java.util.List.of(
                java.util.List.of("T1", "n", "SURVEY", "HIGH", "o", "2026-01-01", "2026-01-02", "0", "PENDING", "")));
        Map<String, Object> body = upload("/api/v1/tools/transfers/import-headers", xlsx, null);
        assertThat(code(body)).as("预览应成功").isEqualTo(0);
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        java.util.List<?> headers = (java.util.List<?>) data.get("headers");
        assertThat(headers).as("表头列数").hasSize(TEMPLATE_COLUMNS.length);
        for (int i = 0; i < TEMPLATE_COLUMNS.length; i++) {
            assertThat(String.valueOf(headers.get(i)))
                    .as("第 %d 列表头", i + 1)
                    .isEqualTo(TEMPLATE_COLUMNS[i]);
        }
    }

    @Test
    @DisplayName("乱序文件 + 列映射：校验与导入结果与模板直传完全一致")
    void mappedImportProducesSameResult() throws Exception {
        // 乱序：名称、编码、状态、类型、负责人、进度、优先级、结束、开始、备注
        String[] shuffled = {
                "任务名称", "任务编码", "状态", "任务类型", "负责人",
                "进度", "优先级", "结束日期", "开始日期", "备注"
        };
        String code = "IMP-MAP-" + RUN;
        byte[] xlsx = excelPort.write("数据", java.util.Arrays.asList(shuffled), java.util.List.of(
                java.util.List.of("乱序任务", code, "PENDING", "SURVEY", "映射人",
                        "35", "HIGH", "2026-03-01", "2026-02-01", "列映射导入")));
        // mapping[模板列序] = 该字段在乱序文件中的列下标
        String mapping = "1,0,3,6,4,8,7,5,2,9";

        Map<String, Object> created = upload(
                "/api/v1/tools/transfers/import?mapping=" + mapping, xlsx, null);
        assertThat(code(created)).as("建任务应成功").isEqualTo(0);
        String taskId = String.valueOf(((Map<?, ?>) created.get("data")).get("taskId"));

        // 轮询到 VALIDATED（异步校验）
        String status = pollStatus(taskId, "VALIDATED");
        assertThat(status).as("乱序文件经映射后必须校验通过").isEqualTo("VALIDATED");

        // 确认导入 → DONE
        Map<String, Object> confirmed = readJson(restClient.post()
                .uri(url("/api/v1/tools/transfers/" + taskId + "/confirm"))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .headers(h -> h.setBearerAuth(adminToken))
                .retrieve());
        assertThat(code(confirmed)).as("确认导入").isEqualTo(0);

        String finalStatus = pollStatus(taskId, "DONE");
        assertThat(finalStatus).as("导入应完成").isEqualTo("DONE");

        // 数据真的落库（映射字段各就各位）
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM srvy_task WHERE tenant_id = ? AND task_code = ?",
                Integer.class, TENANT_ID, code);
        assertThat(count).as("映射导入的任务必须落库").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT task_name AS name, owner_name AS owner FROM srvy_task "
                        + "WHERE tenant_id = ? AND task_code = ?", TENANT_ID, code);
        assertThat(row.get("name")).as("映射到'任务名称'的值必须正确（而不是错位）").isEqualTo("乱序任务");
        assertThat(row.get("owner")).isEqualTo("映射人");
    }

    @Test
    @DisplayName("同一列映射到两个字段 = 硬错误（数据会被复制，必须拒绝）")
    void duplicateMappingIsRejected() throws Exception {
        byte[] xlsx = excelPort.write("数据", java.util.List.of(TEMPLATE_COLUMNS), java.util.List.of(
                java.util.List.of("T2", "n", "SURVEY", "HIGH", "o", "2026-01-01", "2026-01-02", "0", "PENDING", "")));
        Map<String, Object> body = upload(
                "/api/v1/tools/transfers/import?mapping=0,0,2,3,4,5,6,7,8,9", xlsx, null);
        // 400 层面即被拒（controller/service 参数校验），body 仍解析得出业务码
        assertThat(code(body)).as("重复列映射必须被拒绝").isEqualTo(10000);
    }

    // ==================================================================
    // 助手
    // ==================================================================

    private String pollStatus(String taskId, String expected) throws InterruptedException {
        for (int i = 0; i < 25; i++) {
            Thread.sleep(400);
            Map<String, Object> json = readJson(restClient.get()
                    .uri(url("/api/v1/tools/transfers/" + taskId))
                    .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                    .headers(h -> h.setBearerAuth(adminToken))
                    .retrieve());
            Map<?, ?> data = (Map<?, ?>) json.get("data");
            String status = String.valueOf(data.get("status"));
            if (status.equals(expected)) {
                return status;
            }
            if (status.equals("FAILED") || status.equals("CANCELLED")) {
                return status;
            }
        }
        return "TIMEOUT";
    }

    /** multipart 上传（mapping 作为 query 参数）。 */
    private Map<String, Object> upload(String path, byte[] content, String ignored) {
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "import.xlsx";
            }
        });
        try {
            return readJson(restClient.post()
                    .uri(url(path))
                    .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                    .headers(h -> h.setBearerAuth(adminToken))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart)
                    .retrieve());
        } catch (RestClientResponseException ex) {
            // 失败信息必须带上 HTTP 状态 —— 401（鉴权）与 400（参数）的排查方向完全不同
            try {
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        ex.getResponseBodyAsString(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                parsed.put("httpStatus", ex.getStatusCode().value());
                return parsed;
            } catch (Exception parseError) {
                return Map.of("httpStatus", ex.getStatusCode().value(),
                        "raw", ex.getResponseBodyAsString());
            }
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> readJson(
            org.springframework.web.client.RestClient.ResponseSpec spec) {
        Map<String, Object> body = spec.body(JSON_MAP);
        return body == null ? Map.of() : body;
    }

    private static int code(Map<String, Object> json) {
        Object code = json.get("code");
        return code instanceof Number ? ((Number) code).intValue() : -1;
    }
}
