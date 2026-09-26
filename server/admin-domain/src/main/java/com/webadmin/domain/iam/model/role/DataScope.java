package com.webadmin.domain.iam.model.role;

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

    /**
     * 宽窄优先级（从宽到窄）。多角色合并时取靠前者。
     *
     * <p>为什么不用枚举声明顺序：声明顺序是 {@code ALL, CUSTOM, DEPT, DEPT_AND_CHILD, SELF}
     * ——"越靠后越窄"，于是"取 ordinal 最大者"得到的恰好是<b>最窄</b>的 SELF，
     * 与"多角色取并集"完全相反。后果静默且难归因：给用户同时配"全部数据"
     * 与"仅本人"两个角色，他只能看到自己的数据 —— 不报错、不越权，
     * 但功能莫名其妙地少了（实测踩到过）。
     */
    private static final DataScope[] BROADNESS_ORDER = {ALL, DEPT_AND_CHILD, DEPT, CUSTOM, SELF};

    /**
     * 同一用户拥有多个角色时，取并集 —— 即最宽的那个范围。
     *
     * <h3>已知取舍：这里只能取"一个"范围，不是真正的并集</h3>
     * {@link #SELF} 与部门类范围在数学上<b>互不包含</b>：SELF 是"我创建的"，
     * 而那些数据可能落在我不属于的部门里；DEPT 是"我部门的"，其中大部分不是我创建的。
     * 因此"用户同时拥有 SELF 与 DEPT"时，严格语义应当是
     * {@code create_by = 我 OR dept_id = 我的部门} —— 即条件层面的 OR。
     *
     * <p>当前实现按"部门类范围优先于个人范围"的惯例取靠前者（这也是主流中台的常见做法），
     * 代价是<b>个人范围在合并时被覆盖</b>。要真正做到并集，需要把
     * {@code DataScopeConditionBuilder} 的入参从"单个范围"改为"范围集合"并拼 OR ——
     * 那是一次跨层改动，等出现真实诉求再做，而不是现在提前复杂化。
     * 写在这里是为了让后来者知道这是<b>有意为之的取舍</b>，而不是实现遗漏。
     */
    public static DataScope widestOf(Set<DataScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            // 没有任何角色时的兜底：最严格的范围。
            // 注意这里返回 SELF 而非 ALL —— 权限缺失必须收敛到"看得最少"，
            // 而不是默认放开。这是安全默认值（fail-closed）原则。
            return SELF;
        }
        for (DataScope candidate : BROADNESS_ORDER) {
            if (scopes.contains(candidate)) {
                return candidate;
            }
        }
        return SELF;
    }
}
