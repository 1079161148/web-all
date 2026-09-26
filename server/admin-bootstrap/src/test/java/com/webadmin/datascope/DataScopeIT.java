package com.webadmin.datascope;

import static org.assertj.core.api.Assertions.assertThat;

import com.webadmin.application.iam.port.DeptHierarchyCachePort;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * 行级数据权限（四维权限的第三维）集成测试。
 *
 * <h3>为什么必须走真实 HTTP 全链路，而不是只测条件构造器</h3>
 * 数据权限是"拦截器改写 SQL"，它生效依赖一条很长的链路：
 * <pre>
 *   JWT → JwtAuthenticationFilter → SecurityContextHolder
 *       → SecurityCurrentUserAdapter → PermissionResolver（含缓存）
 *       → DataScopePermissionHandler（MyBatis 扩展点）
 *       → DataScopeConditionBuilder → DeptHierarchyLookup → SQL 改写 → count 语句
 * </pre>
 * 只测其中一段（比如 {@code DataScopeConditionBuilder}）会漏掉最常见的一类问题：
 * <b>链路某处没接上</b>——注解漏标、表名/别名不匹配、缓存返回旧范围。
 * 这些的表现都是"条件根本没被追加"，而单元测试照样全绿。
 *
 * <h3>为什么用 RANDOM_PORT + RestClient，而不是 MockMvc</h3>
 * 与 {@code ApiContractFreshnessIT} 保持同一套做法（Spring Boot 4 移除了
 * {@code TestRestTemplate}；MockMvc 的自动配置类不在测试类路径上 —— 都是实测撞到的）。
 * 走真实端口还有额外好处：安全过滤器链与租户上下文过滤器都真正执行，
 * 而这正是"数据权限能否生效"的前提。
 *
 * <p>响应一律按 {@code Map} 结构断言，不注入 {@code ObjectMapper}：
 * Spring Boot 4 默认使用 Jackson 3（{@code tools.jackson.*}），
 * 而 {@code com.fasterxml.jackson} 又因其它依赖在类路径上并存 ——
 * 绑定具体类型会让测试与 Jackson 版本耦合，用 Map 则与版本无关。
 *
 * <h3>为什么每个用例都断言 count 与 list 一致</h3>
 * 设计文档 §7.3 明确要求：拦截器顺序写错（数据权限排在分页之后）时，
 * 生成的 {@code count} 语句会缺少范围条件 —— 现象是<b>"列表 3 条、总数 30"</b>、
 * 分页页数错乱，且不报任何错。断言 {@code total == records.size()} 就是它的哨兵。
 *
 * <h3>测试数据</h3>
 * 容器与数据库在整个测试 JVM 内共享，数据会累积。因此用户名带本次运行后缀
 * （避免重复运行撞唯一键），断言写成"必须出现/必须不出现"的相对事实。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                // 显式声明测试初始密码：避免测试行为随 application.yml 默认值漂移
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class DataScopeIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;

    /** 迁移 V1.0.4 / V1.0.8 建立的部门树：总公司(100) → 研发中心(200)/市场部(300)，研发中心 → 前端组(210)。 */
    private static final long DEPT_DEV = 200L;
    private static final long DEPT_FRONTEND = 210L;
    private static final long DEPT_MARKET = 300L;

    /** 测试用户密码：需满足密码策略（长度、复杂度，且不含弱口令片段）。 */
    private static final String USER_PASSWORD = "It$9pQ2vL";

    /** 本次运行的后缀：同名用户在不同次运行之间不冲突。 */
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    private final RestClient restClient = RestClient.create();

    private String adminToken;
    private List<Long> allMenuIds;

    @Autowired
    private DeptHierarchyCachePort deptHierarchyCache;

    @BeforeEach
    void prepare() throws Exception {
        // 非 HTTP 场景（数据准备）也要显式建立租户上下文：不给测试开后门
        TenantContext.set(TENANT_ID);
        adminToken = login("admin", "Test@123456");
        // "能看哪些页面"与"能看哪些数据行"是两个独立维度：
        // 被测用户必须有接口权限，才谈得上验证数据行可见范围
        allMenuIds = allMenuIds();
    }

    @AfterEach
    void clearTenantContext() {
        // 线程池复用线程时残留的租户上下文会让下一个用例读到错误租户
        TenantContext.clear();
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    @DisplayName("超管不受数据权限约束：能看到所有部门的用户")
    void superAdminSeesAllDepartments() throws Exception {
        String devUser = createUser(adminToken, name("admin_dev"), DEPT_DEV);
        String marketUser = createUser(adminToken, name("admin_mkt"), DEPT_MARKET);

        Map<String, Object> data = pageUsers(adminToken);

        assertCountMatchesList(data);
        assertThat(usernames(data)).contains(devUser, marketUser);
    }

    @Test
    @DisplayName("SELF：只能看到自己创建的数据；创建后立即能看到（回归 create_by 自动填充）")
    void selfScopeSeesOnlyOwnData() throws Exception {
        String owner = createUser(adminToken, name("self_owner"), DEPT_DEV);
        String other = createUser(adminToken, name("self_other"), DEPT_DEV);
        assignRole(owner, roleWithScope("DS_IT_SELF", "SELF", Set.of()));

        String ownerToken = login(owner, USER_PASSWORD);

        Map<String, Object> before = pageUsers(ownerToken);
        assertCountMatchesList(before);
        assertThat(usernames(before)).doesNotContain(owner, other, "admin");

        // ⚠️ 这一步同时回归"审计字段自动填充"：create_by 若没进 INSERT 列清单
        //    （踩过的坑：MyBatis-Plus 只无条件包含标了 fill 注解的字段），
        //    这里会是空列表 —— 而"看不到自己的数据"正是 SELF 范围失效的典型现象
        String created = createUser(ownerToken, name("self_created"), DEPT_DEV);

        Map<String, Object> after = pageUsers(ownerToken);
        assertCountMatchesList(after);
        assertThat(usernames(after)).contains(created);
        assertThat(usernames(after)).doesNotContain(owner, other, "admin");
    }

    @Test
    @DisplayName("DEPT：只看到本部门，不含子部门")
    void deptScopeExcludesChildDepartments() throws Exception {
        String devUser = createUser(adminToken, name("dept_dev"), DEPT_DEV);
        String childUser = createUser(adminToken, name("dept_child"), DEPT_FRONTEND);
        assignRole(devUser, roleWithScope("DS_IT_DEPT", "DEPT", Set.of()));

        Map<String, Object> data = pageUsers(login(devUser, USER_PASSWORD));

        assertCountMatchesList(data);
        assertThat(usernames(data)).contains(devUser);
        assertThat(usernames(data)).doesNotContain(childUser);
        assertAllRecordsInDepartments(data, Set.of(DEPT_DEV));
    }

    @Test
    @DisplayName("DEPT_AND_CHILD：包含直接子部门（回归：前缀匹配曾漏掉直接子部门）")
    void deptAndChildIncludesDirectChild() throws Exception {
        String devUser = createUser(adminToken, name("child_dev"), DEPT_DEV);
        String childUser = createUser(adminToken, name("child_front"), DEPT_FRONTEND);
        String marketUser = createUser(adminToken, name("child_mkt"), DEPT_MARKET);
        assignRole(devUser, roleWithScope("DS_IT_DEPT_CHILD", "DEPT_AND_CHILD", Set.of()));

        Map<String, Object> data = pageUsers(login(devUser, USER_PASSWORD));

        assertCountMatchesList(data);
        // 这条是本用例存在的理由：直接子部门（210）必须在内。
        // 曾经的实现用 `ancestors LIKE '<prefix>,%'` 匹配，恰好漏掉直接子部门
        // （它的 ancestors 恰好等于前缀、没有尾逗号），且不报任何错
        assertThat(usernames(data)).contains(devUser, childUser);
        assertThat(usernames(data)).doesNotContain(marketUser);
        assertAllRecordsInDepartments(data, Set.of(DEPT_DEV, DEPT_FRONTEND));
    }

    @Test
    @DisplayName("CUSTOM：只看到指定部门（fail-closed，不会退化成「看到全部」）")
    void customScopeIsRestricted() throws Exception {
        String devUser = createUser(adminToken, name("custom_dev"), DEPT_DEV);
        String childUser = createUser(adminToken, name("custom_front"), DEPT_FRONTEND);
        assignRole(devUser, roleWithScope("DS_IT_CUSTOM", "CUSTOM", Set.of(DEPT_FRONTEND)));

        Map<String, Object> data = pageUsers(login(devUser, USER_PASSWORD));

        assertCountMatchesList(data);
        assertThat(usernames(data)).contains(childUser);
        assertThat(usernames(data)).doesNotContain(devUser);
        assertAllRecordsInDepartments(data, Set.of(DEPT_FRONTEND));
    }

    @Test
    @DisplayName("权限变更立即生效：改数据范围后无需重新登录（缓存被精确失效）")
    void dataScopeChangeTakesEffectImmediately() throws Exception {
        String devUser = createUser(adminToken, name("switch_dev"), DEPT_DEV);
        String childUser = createUser(adminToken, name("switch_front"), DEPT_FRONTEND);
        assignRole(devUser, roleWithScope("DS_IT_SWITCH", "SELF", Set.of()));

        String token = login(devUser, USER_PASSWORD);
        Map<String, Object> before = pageUsers(token);
        assertCountMatchesList(before);
        assertThat(usernames(before)).doesNotContain(childUser);

        // 同一个令牌、不重新登录，只改角色的数据范围
        roleWithScope("DS_IT_SWITCH", "DEPT_AND_CHILD", Set.of());

        Map<String, Object> after = pageUsers(token);
        assertCountMatchesList(after);
        // 若角色变更没有让权限缓存失效，这里读到的仍是旧范围（看不到子部门用户）
        assertThat(usernames(after)).contains(devUser, childUser);
    }

    @Test
    @DisplayName("调研任务列表同样受数据权限约束，且归属默认取创建人部门")
    void surveyTaskListIsScoped() throws Exception {
        String devUser = createUser(adminToken, name("task_dev"), DEPT_DEV);
        assignRole(devUser, roleWithScope("DS_IT_TASK", "DEPT", Set.of()));

        String devToken = login(devUser, USER_PASSWORD);
        String taskCode = "TASK-IT-" + RUN;
        createSurveyTask(devToken, taskCode, "集成测试任务");

        Map<String, Object> data = pageSurveyTasks(devToken);
        assertCountMatchesList(data);
        // 自己建的任务必须在：归属默认取创建人部门，否则"新建的任务自己都看不到"
        assertThat(taskCodes(data)).contains(taskCode);
        // 且所有可见任务都归属本部门（市场部的种子任务不该出现）
        assertThat(allTaskDepts(data)).containsOnly(DEPT_DEV);
    }

    @Test
    @DisplayName("部门树缓存：查询后写入、结构变更后精确失效、数据范围立即改变")
    void deptTreeCacheIsWrittenAndInvalidated() throws Exception {
        String observer = createUser(adminToken, name("tree_observer"), DEPT_DEV);
        assignRole(observer, roleWithScope("DS_IT_TREE", "DEPT_AND_CHILD", Set.of()));
        String movedDeptName = name("tree_moved");
        long movedDept = createDept(DEPT_DEV, movedDeptName);
        String target = createUser(adminToken, name("tree_target"), movedDept);

        String observerToken = login(observer, USER_PASSWORD);

        // ① 一次查询 → 部门树被按租户写入缓存（此前每次 SQL 改写都要回源两条查询）
        assertThat(usernames(pageUsers(observerToken))).contains(target);
        assertThat(deptHierarchyCache.get(TENANT_ID))
                .as("查询后应已写入部门层级缓存 —— 否则说明缓存没被用上")
                .isPresent();

        // ② 通过应用服务把该部门移出研发中心 → 提交后失效
        moveDept(movedDept, DEPT_MARKET, movedDeptName);
        assertThat(deptHierarchyCache.get(TENANT_ID))
                .as("部门结构变更后缓存应已失效（移动部门会改变整棵子树的路径）")
                .isEmpty();

        // ③ 数据范围立即反映新结构。
        //    这一条是"失效被遗漏"时的兜底断言：若缓存仍持有旧结构，
        //    「本部门及以下」会继续包含已移走的部门下的用户
        assertThat(usernames(pageUsers(observerToken)))
                .as("部门移动后『本部门及以下』不应再包含已移走的部门下的用户")
                .doesNotContain(target);
    }

    @Test
    @DisplayName("数据权限模拟：预览条数 == 真实可见条数；范围取自被模拟角色；无上下文泄漏；超管被拒")
    void roleSimulationMatchesRealityAndDoesNotLeak() throws Exception {
        // 被模拟者：本部门及以下（起点 = 研发中心 200）
        String scopedUser = createUser(adminToken, name("sim_scoped"), DEPT_DEV);
        long scopedRole = roleWithScope("DS_IT_SIM_SCOPED", "DEPT_AND_CHILD", Set.of());
        assignRole(scopedUser, scopedRole);
        String scopedToken = login(scopedUser, USER_PASSWORD);

        // 调用者：另一个用户（仅本人范围）。用它来验证"调用者自身范围不受模拟影响"
        String caller = createUser(adminToken, name("sim_caller"), DEPT_DEV);
        long callerRole = roleWithScope("DS_IT_SIM_CALLER", "SELF", Set.of());
        assignRole(caller, callerRole);
        String callerToken = login(caller, USER_PASSWORD);

        // ⚠️ "真实可见条数"必须在**所有造数完成之后**再取：
        //    上面的造数会在同一个部门下新增用户，先取会让"实际"比"预览"少一条，
        //    表现为一条与数据权限毫无关系的失败（第一次就踩到了）
        long realVisibleUsers = longValue(pageUsers(scopedToken).get("total"));
        long realVisibleTasks = longValue(pageSurveyTasks(scopedToken).get("total"));
        long callerVisibleBefore = longValue(pageUsers(callerToken).get("total"));

        // ① 预览 == 实际：这是本功能唯一的可信度来源。
        //    条件由与真实列表同一个拦截器产生，因此两者必然一致；
        //    若这里不等，说明"预览"另写了一套条件（本功能立刻失去意义）
        // 入参用账号（字符串）：雪花 ID 经浏览器端的数字会丢精度，见 RoleSimulationAppService 的说明
        Map<String, Object> simulated = data(get(
                "/api/v1/iam/roles/" + scopedRole + "/simulation?username=" + scopedUser, callerToken));
        assertThat(text(simulated, "dataScope")).isEqualTo("DEPT_AND_CHILD");
        assertThat(resourceVisible(simulated, "iam_user"))
                .as("预览的用户可见条数必须等于该用户真实打开列表的条数")
                .isEqualTo(realVisibleUsers);
        assertThat(resourceVisible(simulated, "srvy_task"))
                .as("预览的调研任务可见条数必须等于真实列表条数")
                .isEqualTo(realVisibleTasks);
        // 用 contains 而不是 containsExactly：本套用例之间会互相创建部门，
        // 断言"恰好等于"会随执行顺序变化而变成偶发失败
        assertThat(deptIds(simulated))
                .as("生效部门应含『研发中心及其后代』（与其他用例共享同一套种子部门树）")
                .contains(DEPT_DEV, DEPT_FRONTEND);

        // ② 范围来自"被模拟的角色"而不是被模拟用户自带的角色：
        //    换一个「仅本人」角色 + 同一个用户，可见条数应当变成"他本人创建的数据"
        Map<String, Object> selfRole = data(get(
                "/api/v1/iam/roles/" + callerRole + "/simulation?username=" + scopedUser, callerToken));
        assertThat(resourceVisible(selfRole, "iam_user"))
                .as("『仅本人』范围应按被模拟用户自己统计（该用户由管理员创建，故自身无数据）")
                .isLessThan(realVisibleUsers);

        // ③ 无泄漏：模拟上下文只在一次方法调用内生效。
        //    若 ThreadLocal 未被清理，调用者后续请求会继续以"被模拟者"的身份过滤 ——
        //    这里立刻能抓到（这是权限系统最严重的一类事故）
        assertThat(longValue(pageUsers(callerToken).get("total")))
                .as("模拟结束后调用者应恢复自身范围；数值变化说明模拟身份泄漏了")
                .isEqualTo(callerVisibleBefore);

        // ④ 超管角色被拒绝：模拟它必然得到"全部数据"，没有信息量且会误导使用者
        long superAdminRoleId = findRoleId("SUPER_ADMIN");
        assertThat(code(get("/api/v1/iam/roles/" + superAdminRoleId
                + "/simulation?username=" + scopedUser, callerToken)))
                .as("超管角色不参与数据范围，应被明确拒绝而不是返回『全部数据』")
                .isNotEqualTo(0);
    }

    // ==================================================================
    // 数据准备
    // ==================================================================

    private static String name(String base) {
        return "ds_" + base + "_" + RUN;
    }

    private String createUser(String token, String username, Long deptId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("nickname", "集成测试-" + username);
        body.put("password", USER_PASSWORD);
        body.put("deptId", deptId);
        body.put("sex", 2);

        Map<String, Object> json = post("/api/v1/iam/users", token, body);
        assertCodeOk(json, "创建用户 " + username);
        return username;
    }

    private void assignRole(String username, long roleId) throws Exception {
        long userId = findUserId(username);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleIds", List.of(roleId));
        Map<String, Object> json = put("/api/v1/iam/users/" + userId + "/roles", adminToken, body);
        assertCodeOk(json, "给 " + username + " 分配角色");
    }

    /**
     * 创建或复用角色、设置数据范围，返回角色 ID。
     *
     * <p>同时授予全部菜单：数据范围只有在用户<b>能进入页面/接口</b>时才谈得上
     * "能看到哪些行"；不授予会让用例以 403 的形式失败，与数据权限毫无关系。
     */
    private long roleWithScope(String roleKey, String dataScope, Set<Long> deptIds) throws Exception {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("roleKey", roleKey);
        create.put("roleName", "集成测试-" + dataScope);
        create.put("sort", 99);
        create.put("dataScope", dataScope);

        Map<String, Object> created = post("/api/v1/iam/roles", adminToken, create);
        long roleId = code(created) == 0
                ? longValue(created.get("data"))
                // 角色标识租户内唯一：重复运行时复用已有角色，再重设其数据范围
                : findRoleId(roleKey);

        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("menuIds", allMenuIds);
        permissions.put("dataScope", dataScope);
        permissions.put("deptIds", deptIds);

        Map<String, Object> assigned =
                put("/api/v1/iam/roles/" + roleId + "/permissions", adminToken, permissions);
        assertCodeOk(assigned, "设置角色 " + roleKey + " 的数据范围");
        return roleId;
    }

    /** 创建部门并返回其 ID（挂在 {@code parentId} 下，ancestors 由后端计算）。 */
    private long createDept(long parentId, String deptName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", parentId);
        body.put("deptName", deptName);
        body.put("sort", 99);
        body.put("status", "ACTIVE");

        Map<String, Object> json = post("/api/v1/iam/depts", adminToken, body);
        assertCodeOk(json, "创建部门 " + deptName);
        return longValue(json.get("data"));
    }

    /** 修改部门（含移动）。部门名是必填项，因此调用方需把原名传回来。 */
    private void moveDept(long deptId, long newParentId, String deptName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", newParentId);
        body.put("deptName", deptName);
        body.put("status", "ACTIVE");

        assertCodeOk(put("/api/v1/iam/depts/" + deptId, adminToken, body), "移动部门");
    }

    private void createSurveyTask(String token, String taskCode, String taskName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskCode", taskCode);
        body.put("taskName", taskName);
        body.put("taskType", "SURVEY");
        body.put("priority", "MEDIUM");
        body.put("progress", 0);
        body.put("status", "PENDING");

        assertCodeOk(post("/api/v1/survey/tasks", token, body), "创建调研任务");
    }

    // ==================================================================
    // HTTP 辅助
    // ==================================================================

    private String login(String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);

        Map<String, Object> json = post("/api/v1/auth/login", null, body);
        assertCodeOk(json, "登录 " + username);
        return (String) data(json).get("accessToken");
    }

    private Map<String, Object> get(String path, String token) throws Exception {
        return read(token == null
                ? restClient.get().uri(url(path)).header("X-Tenant-Id", String.valueOf(TENANT_ID))
                : restClient.get().uri(url(path))
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .header("Authorization", "Bearer " + token));
    }

    private Map<String, Object> post(String path, String token, Object body) throws Exception {
        RestClient.RequestBodySpec spec = restClient.post().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return read(spec.body(body));
    }

    private Map<String, Object> put(String path, String token, Object body) throws Exception {
        RestClient.RequestBodySpec spec = restClient.put().uri(url(path))
                .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return read(spec.body(body));
    }

    /** 以 {@code Map} 结构读取响应：与 Jackson 大版本解耦（见类注释）。 */
    private Map<String, Object> read(RestClient.RequestHeadersSpec<?> spec) {
        Map<String, Object> body = spec.retrieve().body(JSON_MAP);
        return body == null ? Map.of() : body;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private List<Long> allMenuIds() throws Exception {
        // 菜单接口返回的是**数组**（不是分页结构）：这里用 listData 而不是 records
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> menu : listData(get("/api/v1/iam/menus", adminToken))) {
            ids.add(longValue(menu.get("id")));
        }
        assertThat(ids).as("菜单列表为空，无法为测试角色授予页面权限").isNotEmpty();
        return ids;
    }

    private Map<String, Object> pageUsers(String token) throws Exception {
        return data(get("/api/v1/iam/users?page=1&size=200", token));
    }

    private Map<String, Object> pageSurveyTasks(String token) throws Exception {
        return data(get("/api/v1/survey/tasks?page=1&size=200", token));
    }

    private long findUserId(String username) throws Exception {
        for (Map<String, Object> node : records(pageUsers(adminToken))) {
            if (username.equals(node.get("username"))) {
                return longValue(node.get("id"));
            }
        }
        throw new AssertionError("测试用户不存在：" + username);
    }

    private long findRoleId(String roleKey) throws Exception {
        for (Map<String, Object> node : records(data(get("/api/v1/iam/roles?page=1&size=100", adminToken)))) {
            if (roleKey.equals(node.get("roleKey"))) {
                return longValue(node.get("id"));
            }
        }
        throw new AssertionError("测试角色不存在：" + roleKey);
    }

    // ==================================================================
    // 断言与结构辅助
    // ==================================================================

    /**
     * count 与 list 必须一致（设计文档 §7.3 的硬性要求）。
     *
     * <p>拦截器顺序写错时，{@code count} 语句会缺少数据范围条件 ——
     * 表现为"列表 3 条、总数 30"，且不报任何错。这条断言是它的哨兵。
     */
    private void assertCountMatchesList(Map<String, Object> data) {
        int total = (int) longValue(data.get("total"));
        int actual = records(data).size();
        assertThat(total)
                .as("count 与 list 不一致：total=%s 但实际返回 %s 条"
                        + "（拦截器顺序或条件施加有问题）", total, actual)
                .isEqualTo(actual);
    }

    private void assertCodeOk(Map<String, Object> response, String action) {
        assertThat(code(response))
                .as("%s 失败：%s", action, response.get("msg"))
                .isEqualTo(0);
    }

    private static int code(Map<String, Object> response) {
        return (int) longValue(response.getOrDefault("code", -1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        Object value = response.get("data");
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> data) {
        Object value = data.get("records");
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    /** 读取"响应体里直接就是一个数组"的接口（如菜单列表）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listData(Map<String, Object> response) {
        Object value = response.get("data");
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Map<String, Object> node, String key) {
        Object value = node.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 从模拟结果里取某资源的可见条数。 */
    @SuppressWarnings("unchecked")
    private long resourceVisible(Map<String, Object> simulation, String resource) {
        Object value = simulation.get("resources");
        if (!(value instanceof List<?> list)) {
            throw new AssertionError("模拟结果缺少 resources 字段：" + simulation);
        }
        for (Object item : list) {
            Map<String, Object> node = (Map<String, Object>) item;
            if (resource.equals(text(node, "resource"))) {
                return longValue(node.get("visible"));
            }
        }
        throw new AssertionError("模拟结果中没有资源 " + resource + "：" + simulation);
    }

    /** 从模拟结果里取生效部门 ID（保持后端返回顺序）。 */
    @SuppressWarnings("unchecked")
    private List<Long> deptIds(Map<String, Object> simulation) {
        Object value = simulation.get("depts");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            ids.add(longValue(((Map<String, Object>) item).get("deptId")));
        }
        return ids;
    }

    private List<String> usernames(Map<String, Object> data) {
        List<String> names = new ArrayList<>();
        records(data).forEach(node -> names.add(text(node, "username")));
        return names;
    }

    private List<String> taskCodes(Map<String, Object> data) {
        List<String> codes = new ArrayList<>();
        records(data).forEach(node -> codes.add(text(node, "taskCode")));
        return codes;
    }

    private List<Long> allTaskDepts(Map<String, Object> data) {
        List<Long> depts = new ArrayList<>();
        records(data).forEach(node -> depts.add(longValue(node.get("deptId"))));
        return depts;
    }

    private void assertAllRecordsInDepartments(Map<String, Object> data, Set<Long> deptIds) {
        records(data).forEach(node -> assertThat(longValue(node.get("deptId")))
                .as("用户 %s 不属于允许的部门集合 %s", text(node, "username"), deptIds)
                .isIn(deptIds));
    }
}
