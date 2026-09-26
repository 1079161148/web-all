package com.webadmin.application.iam.dto;

import com.webadmin.application.iam.port.RoleSimulationPort.DeptBrief;
import com.webadmin.application.iam.port.RoleSimulationPort.ResourceVisibleCount;
import java.util.List;

/**
 * 数据权限模拟结果。
 *
 * <h3>为什么只返回条数，不返回数据行</h3>
 * 模拟的用途是回答"这个角色能看到<b>多少</b>数据、范围到哪"，
 * 而不是"把那些数据给我看看"。后者等于给了一个
 * <b>"用任意角色身份读任意数据"的口子</b> —— 只要拥有模拟权限，
 * 就能绕过自己本不该拥有的可见性。
 *
 * <p>条数 + 生效部门已经足够回答"范围配置得对不对"这个真正的运维问题；
 * 而要看具体内容，正确做法是给那个角色一个真实的测试账号。
 *
 * @param dataScope 数据范围枚举名（本地化文案由前端字典提供，后端不重复维护一份标签）
 * @param depts     生效部门；{@code ALL} / {@code SELF} 时为空列表（语义正确，非"没查到"）
 * @param resources 各资源的可见条数
 */
public record RoleSimulationView(
        long roleId,
        String roleKey,
        String roleName,
        long userId,
        String username,
        String nickname,
        String dataScope,
        Long deptId,
        String deptName,
        List<DeptBrief> depts,
        List<ResourceVisibleCount> resources) {
}
