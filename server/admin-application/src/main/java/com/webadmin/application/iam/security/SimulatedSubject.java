package com.webadmin.application.iam.security;

import com.webadmin.domain.iam.model.role.DataScope;
import java.util.Set;

/**
 * 数据权限模拟主体：一次"以某角色预览数据范围"时的有效身份与范围。
 *
 * <h3>为什么需要它，而不只是"换一个角色"</h3>
 * 数据范围里有三类范围<b>依赖具体的人</b>，不是一个角色就能表达的：
 * <ul>
 *   <li>{@code SELF} → 条件为 {@code create_by = 当前用户}</li>
 *   <li>{@code DEPT} → 条件是"用户所属部门"，所以要取<b>被模拟用户</b>的部门</li>
 *   <li>{@code DEPT_AND_CHILD} → 从被模拟用户的部门往下展开</li>
 * </ul>
 * 因此模拟的完整输入是<b>「角色 + 用户」</b>：角色的范围（scope / 自定义部门）
 * 加上用户的位置（userId / deptId）。只给角色无法回答"这个角色的人能看到什么"。
 *
 * <h3>为什么超管不在模拟范围内</h3>
 * 超管的数据范围被解析为 {@code ALL}（见 {@code PermissionResolver}），
 * 模拟它只会得到"全部数据"这一必然结论，没有信息量；
 * 更重要的是它会让人误以为"这个角色能看到全部"。
 * 调用方（{@code RoleSimulationAppService}）会直接拒绝模拟超管角色。
 *
 * @param userId        被模拟的用户 ID（{@code SELF} 范围据此判定）
 * @param deptId        被模拟用户所属部门，可能为 null（未分配部门）
 * @param scope         被模拟角色声明的数据范围
 * @param customDeptIds {@code CUSTOM} 范围下的部门集合
 */
public record SimulatedSubject(long userId, Long deptId, DataScope scope, Set<Long> customDeptIds) {

    public SimulatedSubject {
        // 范围缺失 → 收敛到最窄（fail-closed），与运行时 DataScopeConditionBuilder 的取向一致：
        // 预览结果偏窄会被立刻发现，偏宽则可能被当成"没问题"而长期留存
        scope = scope == null ? DataScope.SELF : scope;
        customDeptIds = customDeptIds == null ? Set.of() : Set.copyOf(customDeptIds);
    }
}
