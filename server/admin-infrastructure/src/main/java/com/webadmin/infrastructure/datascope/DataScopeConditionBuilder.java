package com.webadmin.infrastructure.datascope;

import com.webadmin.domain.iam.model.role.DataScope;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把数据范围翻译成 SQL 条件片段。
 *
 * <h3>为什么返回字符串而不是直接构造 JSqlParser 的 AST</h3>
 * 手工构造 {@code InExpression} / {@code ExpressionList} 的写法在不同 JSqlParser
 * 版本间差异较大（{@code ItemsList} 接口被移除、构造器签名变更），
 * 而 {@code CCJSqlParserUtil.parseCondExpression(String)} 的签名长期稳定。
 *
 * <p>安全性上不构成注入风险：进入条件串的<b>全部是 long 型数值</b>，
 * 来源是我们自己的数据库（部门 ID、用户 ID），没有任何用户可控的字符串拼接。
 * 列名来自 {@code @DataScope} 注解的字面量，由开发者书写、不来自请求。
 *
 * <h3>空集合必须是"永假"而不是"无条件"</h3>
 * 这是本类最容易写错、后果最严重的一处：
 * <ul>
 *   <li>若把 {@code CUSTOM} 范围下的空部门集合渲染成 {@code dept_id IN ()} → SQL 语法错误</li>
 *   <li>若"贴心地"跳过条件 → 该角色<b>看到全部数据</b>，是一个静默的越权</li>
 * </ul>
 * 因此统一渲染为 {@code 1 = 0}（看不到任何数据）。用户会立刻反馈"我什么都看不到"，
 * 这是可发现、可修复的；而越权是发现不了的。
 */
@Component
@RequiredArgsConstructor
public class DataScopeConditionBuilder {

    /** 永假条件：用于"范围内没有任何部门"与"无法解析条件"两种降级场景。 */
    public static final String ALWAYS_FALSE = "1 = 0";

    private final DeptHierarchyLookup deptHierarchyLookup;

    /**
     * 构建条件片段。
     *
     * @param deptColumn     部门列（可能带表别名限定）
     * @param userColumn     创建人列
     * @param scope          数据范围；为 null 时按最窄处理
     * @param userId         当前用户 ID
     * @param deptId         当前用户所属部门，可能为 null
     * @param customDeptIds  {@code CUSTOM} 范围的部门集合
     * @return SQL 条件片段；{@code null} 表示<b>无需过滤</b>（仅 ALL 范围返回 null）
     */
    public String build(String deptColumn, String userColumn, DataScope scope,
                        long userId, Long deptId, Set<Long> customDeptIds) {
        if (scope == null) {
            // 范围未知 → 收到最窄。绝不默认 ALL。
            return userColumn + " = " + userId;
        }
        return switch (scope) {
            case ALL -> null;
            case SELF -> userColumn + " = " + userId;
            case DEPT -> deptId == null
                    // 用户没有部门却要求"本部门数据"：无法表达该语义。
                    // 降级为 SELF 而不是返回 ALWAYS_FALSE ——
                    // 后者会让"未分配部门"的员工连自己的数据都看不到，
                    // 而这类用户（新入职、跨部门借调）恰恰很常见。
                    ? userColumn + " = " + userId
                    : deptColumn + " = " + deptId;
            case DEPT_AND_CHILD -> deptId == null
                    ? userColumn + " = " + userId
                    // 展开为"本部门 + 全部后代部门"的 ID 集合，
                    // 而不是拼 SQL 子查询（原因见 DeptHierarchyLookup 的注释）
                    : inClause(deptColumn, deptHierarchyLookup.selfAndDescendants(deptId));
            case CUSTOM -> inClause(deptColumn, customDeptIds);
        };
    }

    /**
     * 渲染 {@code IN} 条件；空集合渲染为永假。
     */
    public String inClause(String column, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ALWAYS_FALSE;
        }
        String values = ids.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return values.isEmpty() ? ALWAYS_FALSE : column + " IN (" + values + ")";
    }
}
