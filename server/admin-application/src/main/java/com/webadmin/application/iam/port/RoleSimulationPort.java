package com.webadmin.application.iam.port;

import com.webadmin.domain.iam.model.role.DataScope;
import java.util.List;
import java.util.Set;

/**
 * 数据权限模拟所需的数据访问端口。
 *
 * <h3>{@link #countVisibleResources()} 为什么没有参数、且必须在模拟上下文中调用</h3>
 * 它不接收"范围"之类的入参，而是执行<b>真实的列表统计查询</b> ——
 * 过滤条件由 MyBatis 拦截器根据"当前主体"施加
 * （模拟上下文见 {@code RoleSimulationContext}）。
 *
 * <p>这是刻意设计的：如果本端口接收 {@code SimulatedSubject} 并自己拼 WHERE 条件，
 * 就会出现两套条件实现（运行时的、预览的），而它们漂移的表现是
 * "预览与实际不符" —— 一个让整个功能失去意义的故障，且很难被发现。
 *
 * <h3>资源清单</h3>
 * 实现方<b>应当</b>把每一个接了数据权限的资源都列进来。
 * 清单短了不会报错，只会让预览少显示一项 —— 因此新增
 * {@code @DataScope} 的列表接口时，记得同步这里（这也是唯一需要人工维护的地方）。
 */
public interface RoleSimulationPort {

    /**
     * 统计当前（模拟）主体在各资源上可见的条数。
     *
     * <p><b>只能在 {@code RoleSimulationContext.runWith(...)} 内调用</b>：
     * 上下文之外调用得到的是"调用者本人"的可见条数，没有任何意义。
     */
    List<ResourceVisibleCount> countVisibleResources();

    /**
     * 解析主体生效的部门（用于"可见部门"展示）。
     *
     * @param scope         数据范围
     * @param deptId        主体所属部门（{@code DEPT} / {@code DEPT_AND_CHILD} 的展开起点）
     * @param customDeptIds {@code CUSTOM} 范围的部门集合
     * @return 生效部门（含名称）；{@code ALL} 或 {@code SELF} 返回空列表 ——
     *         它们不由部门集合表达范围，调用方会给出相应说明文案
     */
    List<DeptBrief> effectiveDepts(DataScope scope, Long deptId, Set<Long> customDeptIds);

    /** 单个资源的可见条数。 */
    record ResourceVisibleCount(String resource, String label, long visible) {
    }

    /** 部门简要信息。 */
    record DeptBrief(long deptId, String deptName) {
    }
}
