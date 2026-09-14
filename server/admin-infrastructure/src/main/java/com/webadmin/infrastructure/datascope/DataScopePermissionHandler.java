package com.webadmin.infrastructure.datascope;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.webadmin.application.iam.port.PermissionCachePort.CachedPermissions;
import com.webadmin.application.iam.security.PermissionResolver;
import com.webadmin.application.security.CurrentUser;
import com.webadmin.application.security.CurrentUserPort;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import org.springframework.stereotype.Component;

/**
 * 数据权限处理器：把角色的 {@code dataScope} 翻译成 SQL 的行级过滤条件。
 */
@Slf4j
@Component
public class DataScopePermissionHandler implements MultiDataPermissionHandler {

    private final DataScopeAnnotationRegistry annotationRegistry;
    private final CurrentUserPort currentUserPort;
    private final PermissionResolver permissionResolver;
    private final DataScopeConditionBuilder conditionBuilder;

    /**
     * 构造器注入，三个依赖<b>必须</b>是 {@link Lazy} —— 否则应用根本起不来。
     *
     * <h3>为什么会产生循环依赖（这是一个真实踩到的坑）</h3>
     * <pre>
     *   sqlSessionFactory          （MyBatis 构建期需要一个完整的拦截器链）
     *     └─ MybatisPlusInterceptor
     *          └─ DataScopePermissionHandler      ← 本类
     *               └─ PermissionResolver          （解析角色 → 需要查数据库）
     *                    └─ RoleRepository
     *                         └─ RoleMapper
     *                              └─ sqlSessionFactory   ← 回到起点，形成环
     * </pre>
     * 根本矛盾在于：<b>数据权限必须在 SQL 层施加，而施加者却需要先查一次数据库
     * 才能知道该施加什么条件</b>。这个矛盾没有任何设计技巧能消除，
     * 只能把"解析数据范围"这件事推迟到<b>真正执行查询时</b>。
     *
     * <p>{@code @Lazy} 注入的是代理对象，首次调用 {@code getSqlSegment} 时才真正解析 Bean。
     * 而那一刻 SqlSessionFactory 早已构建完成，环自然断开。
     *
     * <h3>为什么不用 ObjectProvider 或 @Lazy 标在 Bean 定义上</h3>
     * 三者都能解决本问题，选择 {@code @Lazy} 参数的理由是它把"此处必须惰性"
     * 这个约束<b>写在了最靠近依赖的地方</b> —— 谁读到这个构造器都会看到它，
     * 而 {@code @Lazy} 标在外部配置类里则容易被后续重构无意中删掉，
     * 症状是"应用启动失败"，但报错信息指向 sqlSessionFactory，排查方向会完全跑偏。
     *
     * <p>同理：{@link DataScopeConditionBuilder} 也走惰性 ——
     * 它依赖 {@code DeptHierarchyLookup} → {@code DeptMapper} → SqlSessionFactory，
     * 同样在环上。
     */
    public DataScopePermissionHandler(
            DataScopeAnnotationRegistry annotationRegistry,
            @Lazy CurrentUserPort currentUserPort,
            @Lazy PermissionResolver permissionResolver,
            @Lazy DataScopeConditionBuilder conditionBuilder) {
        this.annotationRegistry = annotationRegistry;
        this.currentUserPort = currentUserPort;
        this.permissionResolver = permissionResolver;
        this.conditionBuilder = conditionBuilder;
    }

    /**
     * 返回需要追加到 {@code WHERE} 的条件；返回 {@code null} 表示<b>不加任何条件</b>。
     *
     * <p>这是 MyBatis-Plus {@code DataPermissionInterceptor} 的扩展点，
     * <b>对每一条 SQL 都会调用</b>（每条 SQL 的每张表各一次）。
     * 因此本方法的第一件事就是尽可能早地退出 ——
     * 绝大多数查询没有 {@code @DataScope} 注解，应当在查表一次之后就返回。
     *
     * @param table             当前正在处理的表
     * @param where             原始 where 条件（本实现不修改它，由框架负责拼接）
     * @param mappedStatementId Mapper 方法全限定名，用于定位注解
     */
    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        // ① 最快的退出路径：没有注解 → 不做任何事
        Optional<com.webadmin.common.datascope.DataScope> found =
                annotationRegistry.find(mappedStatementId);
        if (found.isEmpty()) {
            return null;
        }
        com.webadmin.common.datascope.DataScope annotation = found.get();

        // ② 注解限定了表名时，只对该表生效。
        //    多表联查中若不限定，会给关联表（如 org_dept 自身）也套上部门条件，
        //    造成"部门列表里只看得见自己"这类难以理解的现象。
        //
        //    ⚠️ 必须同时比对「表名」与「别名」。SQL 里写成 `FROM iam_user u` 时，
        //       JSqlParser 解析出的 Table 既有 name=iam_user 也有 alias=u，
        //       而不同版本/不同语句形态下 getName() 返回的是哪一个并不稳定。
        //       只比对 name 会出现：**条件匹配不上 → 直接返回 null → 数据权限静默失效**。
        //       这是本类最危险的一处 —— 它不报错，只是列表里多出别的部门的数据。
        String targetTable = annotation.table();
        if (targetTable != null && !targetTable.isBlank() && !matchesTable(table, targetTable)) {
            return null;
        }

        // ③ 无登录上下文（定时任务、公开接口）→ 不加条件。
        //    这里返回 null 而非永假：数据权限是"按登录身份收窄"，没有身份就没有收窄依据；
        //    而租户隔离由 TenantLineInnerInterceptor 独立保证，不会因此跨租户。
        Optional<CurrentUser> currentOpt = currentUserPort.currentUser();
        if (currentOpt.isEmpty()) {
            return null;
        }
        CurrentUser current = currentOpt.get();

        CachedPermissions permissions =
                permissionResolver.resolve(current.tenantId(), current.userId());

        // ④ 超管豁免
        if (permissions.superAdmin() && annotation.ignoreSuperAdmin()) {
            return null;
        }

        // 用 var 承接枚举，避免与同名注解产生 import 冲突
        var scope = permissions.widestDataScope();

        String condition = conditionBuilder.build(
                annotation.deptAlias(),
                annotation.userAlias(),
                scope,
                current.userId(),
                permissions.deptId(),
                permissions.customDeptIds());

        if (condition == null) {
            // ALL 范围：确实无需过滤
            return null;
        }

        try {
            return CCJSqlParserUtil.parseCondExpression(condition);
        } catch (Exception ex) {
            // ⚠️ 解析失败必须收敛到"永假"，绝不能返回 null。
            // 返回 null 意味着**不过滤**，即把数据范围放开；
            // 而条件是程序生成的，解析失败说明代码有 bug，此时唯一安全的选择是拒绝返回数据。
            log.error("数据权限条件解析失败，已降级为「无匹配数据」以避免越权。"
                            + "condition={} mappedStatementId={}", condition, mappedStatementId, ex);
            return parseAlwaysFalse();
        }
    }

    /** 表名或别名任一命中即视为目标表（原因见 {@code getSqlSegment} 中的说明）。 */
    private static boolean matchesTable(Table table, String targetTable) {
        if (targetTable.equalsIgnoreCase(table.getName())) {
            return true;
        }
        return table.getAlias() != null
                && table.getAlias().getName() != null
                && targetTable.equalsIgnoreCase(table.getAlias().getName());
    }

    /** 解析并缓存永假条件。失败返回 null 时由调用方继续降级（见上方日志告警）。 */
    private Expression parseAlwaysFalse() {
        try {
            return CCJSqlParserUtil.parseCondExpression(DataScopeConditionBuilder.ALWAYS_FALSE);
        } catch (Exception ex) {
            log.error("无法构造永假降级条件，数据权限将失效，请立即排查", ex);
            return null;
        }
    }
}
