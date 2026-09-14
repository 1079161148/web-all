package com.webadmin.domain.iam.model.role;

import java.util.EnumSet;
import java.util.Set;

/**
 * 数据范围（四维权限中的「行级数据权限」）。
 *
 * <h3>这里只表达"是什么"，不表达"怎么拼 SQL"</h3>
 * 枚举本身是领域概念，只负责声明语义与包含关系；
 * 把语义翻译成 SQL 条件属于基础设施关注点，放在
 * {@code infrastructure} 的 {@code DataPermissionInterceptor} 里。
 *
 * <p>这条边界很重要：一旦领域枚举里出现 {@code "dept_id IN (...)"} 这类 SQL 片段，
 * 领域层就被持久化细节污染了，而 ArchUnit 也拦不住 —— 因为它只是字符串。
 *
 * <h3>包含关系</h3>
 * {@link #ALL} 包含其余全部；{@link #DEPT_AND_CHILD} 包含 {@link #DEPT}；
 * {@link #CUSTOM} / {@link #SELF} 之间互不包含。
 */
public enum DataScope {

    /** 全部数据（通常只有超管或平台运营角色拥有）。 */
    ALL,

    /** 自定义：由 {@code iam_role_dept} 指定可见部门集合。 */
    CUSTOM,

    /** 本部门。 */
    DEPT,

    /** 本部门及其所有子部门（基于 {@code ancestors} 前缀匹配）。 */
    DEPT_AND_CHILD,

    /** 仅本人创建的数据。 */
    SELF,
    ;

    /** 是否为最宽的范围（用于判断"无需拼任何条件"）。 */
    public boolean isUnrestricted() {
        return this == ALL;
    }

    /** 严格包含关系：本范围是否完全覆盖另一个范围。 */
    public boolean covers(DataScope other) {
        if (this == other || this == ALL) {
            return true;
        }
        return this == DEPT_AND_CHILD && other == DEPT;
    }

    /** 同一用户拥有多个角色时，取并集 —— 即最宽的那个范围。 */
    public static DataScope widestOf(Set<DataScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            // 没有任何角色时的兜底：最严格的范围。
            // 注意这里返回 SELF 而非 ALL —— 权限缺失必须收敛到"看得最少"，
            // 而不是默认放开。这是安全默认值（fail-closed）原则。
            return SELF;
        }
        return EnumSet.copyOf(scopes).stream()
                .max((a, b) -> Integer.compare(a.ordinal(), b.ordinal()))
                // ordinal 顺序即声明顺序：ALL 最宽、SELF 最窄，故取 ordinal 最大者
                .orElse(SELF);
    }
}
