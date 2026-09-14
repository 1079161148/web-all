package com.webadmin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webadmin.testsupport.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * 契约新鲜度门禁：仓库里的 {@code packages/api/openapi.json} 必须与后端<b>实际产出</b>的
 * OpenAPI 文档一致。
 *
 * <h3>这个测试补的是哪一段缺口</h3>
 * 原来的流水线只有 {@code pnpm gen:check}，它校验的是「契约 → 前端客户端」。
 * 而「后端 → 契约」这一段<b>完全没有人管</b>。缺口导致的真实后果：
 * <ol>
 *   <li>有人新增了一个接口、改了某个 DTO 的字段，但忘了更新 {@code openapi.json}</li>
 *   <li>{@code gen:check} <b>丝滑通过</b> —— 它只比对契约与客户端，而这两者确实是一致的</li>
 *   <li>前端拿到一份<b>缺少新接口</b>的类型定义。编译期一切正常，
 *       直到运行时调用才发现方法不存在</li>
 * </ol>
 * 这类问题在 CI 上完全静默，只能靠人记得去更新契约 —— 而"靠人记得"不是门禁。
 *
 * <h3>为什么必须剥掉 {@code servers}</h3>
 * springdoc 会<b>根据本次请求的 Host</b> 自动生成 {@code servers}
 * （内容形如 {@code {"url":"http://localhost:8080","description":"Generated server url"}}）。
 * 本测试跑在随机端口上，若不剥掉，live 侧永远是 {@code http://localhost:<随机端口>}，
 * 与仓库里的 {@code http://localhost:8080} 必然不同 ——
 * 门禁会 <b>100% 误报</b>，然后被所有人忽略或被注释掉，<b>比没有还糟</b>。
 *
 * <p>结论：<b>与运行环境相关的字段不是契约的一部分，必须排除在比对之外。</b>
 * 部署地址由各环境的配置决定，不该被一份提交进仓库的文件钉死。
 *
 * <h3>为什么用 {@code NON_EXTENSIBLE} 而不是 {@code STRICT}</h3>
 * {@code STRICT} 会校验数组顺序，但 {@code required: ["a","b"]}、
 * parameters 列表的顺序对契约语义毫无影响，springdoc 也不承诺顺序稳定。
 * 用 STRICT 会得到一个"改了无关代码就变红"的脆弱门禁，最终同样会被绕过。
 * <b>{@code NON_EXTENSIBLE} 恰好是我们要的语义：不多不少，但顺序无所谓。</b>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 与 AuthAppServiceIT 保持一致：显式声明测试用的初始密码，
                // 避免测试行为随 application.yml 的默认值漂移
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class ApiContractFreshnessIT extends AbstractIntegrationTest {

    /** 契约端点。SecurityConfig 的 PUBLIC_ENDPOINTS 里已放行，无需认证。 */
    private static final String SPEC_ENDPOINT = "/v3/api-docs";

    /** 契约文件相对仓库根目录的位置。 */
    private static final String SPEC_RELATIVE_PATH = "packages/api/openapi.json";

    /**
     * 视为"由运行环境决定、不属于契约"的顶层字段。
     *
     * <p>新增条目时必须说明理由 —— 往这里加字段等于放宽门禁，
     * 加多了会让门禁失去意义。
     */
    private static final String[] ENVIRONMENT_DEPENDENT_FIELDS = {"servers"};

    /** 更新模式开关：{@code -Dcontract.update=true} 时把实时契约写回仓库文件。 */
    private static final String UPDATE_FLAG = "contract.update";

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("提交的 openapi.json 必须与后端实际产出一致（否则说明忘了更新契约）")
    void committed_spec_must_match_live_spec() throws Exception {
        Path repoRoot = findRepoRoot();
        Path specFile = repoRoot.resolve(SPEC_RELATIVE_PATH);

        String liveSpec = stripEnvironmentDependentFields(fetchLiveSpec());

        // 恒久产物：把实时契约落到 target 下。
        // 门禁失败时，开发者可以直接 diff 这个文件与仓库里的契约来定位差异，
        // 而不必自己起应用、自己 curl —— 把排查成本降到最低。
        Path liveDump = repoRoot.resolve("server/admin-bootstrap/target/live-openapi.json");
        Files.createDirectories(liveDump.getParent());
        Files.writeString(liveDump, liveSpec, StandardCharsets.UTF_8);

        assertThat(specFile)
                .as("契约文件不存在：%s。请先执行契约生成流程（见类注释）", specFile)
                .exists();

        if (Boolean.getBoolean(UPDATE_FLAG)) {
            Files.writeString(specFile, liveSpec, StandardCharsets.UTF_8);
            System.out.println("""

                    [契约已更新] 已把实时契约写回 %s

                    这是一次「有意变更」，但仍有两件事必须做完 —— 否则门禁依然是红的：
                      ① 检查这个 diff 里没有你不认识的变化（可能是别人加的东西）
                      ② 执行 pnpm gen:api 重新生成前端客户端，并提交 packages/api/src/generated
                    """.formatted(specFile));
            return;
        }

        String committedSpec = stripEnvironmentDependentFields(
                Files.readString(specFile, StandardCharsets.UTF_8));

        try {
            JSONAssert.assertEquals(committedSpec, liveSpec, JSONCompareMode.NON_EXTENSIBLE);
        } catch (AssertionError mismatch) {
            // 失败信息必须包含"怎么修"。否则开发者只能看到一句"契约不一致"，
            // 然后去翻 CI 配置找原因 —— 那是门禁最不该省的一步。
            throw new AssertionError("""

                    ============================================================
                    契约不一致：后端实际产出的 OpenAPI 与 %s 不同。

                    实时契约已写入：%s

                    如果你确实改了接口（新增/删除/改字段），这是预期行为，执行：
                        mvn -pl admin-bootstrap verify -Dcontract.update=true
                        pnpm gen:api
                      然后提交 openapi.json 与 packages/api/src/generated 的改动。

                    如果你**没有**改接口，说明有异常变更 —— 请先查清来源，
                    不要直接跑 update 把它盖过去（那会掩盖真实的接口外泄）。
                    ============================================================

                    """.formatted(SPEC_RELATIVE_PATH, liveDump) + mismatch.getMessage(), mismatch);
        }
    }

    /**
     * 通过真实 HTTP 抓取实时契约（而不是 MockMvc）—— 契约测试应走与前端相同的链路。
     *
     * <p>用 {@code RestClient} 而非 {@code TestRestTemplate}：
     * Spring Boot 4 <b>已移除</b> {@code org.springframework.boot.test.web.client.TestRestTemplate}，
     * {@code RestClient} 是 Spring 7 的替代品（实测确认，编译期即报「程序包不存在」）。
     */
    private String fetchLiveSpec() {
        String body = RestClient.create()
                .get()
                .uri("http://localhost:" + port + SPEC_ENDPOINT)
                .retrieve()
                .body(String.class);
        assertThat(body)
                .as("契约端点 %s 返回空内容：请确认 springdoc 仍在依赖中、"
                        + "且 SecurityConfig 的 PUBLIC_ENDPOINTS 仍放行 /v3/api-docs/**", SPEC_ENDPOINT)
                .isNotBlank();
        return body;
    }

    /**
     * 剥掉由运行环境决定的字段（见类注释中关于 {@code servers} 的说明）。
     *
     * <p>若字段不存在则原样返回 —— 这样无论 springdoc 将来是否还生成它，本方法都成立。
     */
    private static String stripEnvironmentDependentFields(String specJson) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(specJson);
        for (String field : ENVIRONMENT_DEPENDENT_FIELDS) {
            root.remove(field);
        }
        return JSON.writeValueAsString(root);
    }

    /**
     * 从当前工作目录向上查找仓库根目录。
     *
     * <p>用 {@code pnpm-workspace.yaml} 作为标记，而不是写死 {@code ../../}：
     * Maven 在不同场景下的工作目录并不一致（直接跑模块 vs 从根目录跑聚合构建），
     * 写死相对层级会在其中一种场景下静默指向错误的位置。
     */
    private static Path findRepoRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return Stream.iterate(start, path -> path != null && path.getParent() != null,
                        Path::getParent)
                .filter(path -> Files.exists(path.resolve("pnpm-workspace.yaml")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "从 " + start + " 向上未找到 pnpm-workspace.yaml，无法定位仓库根目录。"
                                + "契约门禁需要能读到 packages/api/openapi.json。"));
    }
}
