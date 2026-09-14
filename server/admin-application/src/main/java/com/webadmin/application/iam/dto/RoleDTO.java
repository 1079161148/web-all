package com.webadmin.application.iam.dto;

import java.time.Instant;
import java.util.List;

/**
 * 角色读模型（应用层内部类型）。
 *
 * <p>与 {@code UserDTO} 同样<b>不带 swagger 注解</b> —— OpenAPI 契约类型属于
 * interfaces 层，详见 {@code UserDTO} 的类注释。
 *
 * @param userCount 关联用户数。这个字段是刻意带上的：
 *                  删除角色前的占用校验需要它，而在列表里展示它能让管理员<b>提前看到</b>
 *                  "这个角色还有人用"，而不是点删除后才被拒绝。
 *                  <b>把拒绝的理由提前展示出来，比事后报错更友好。</b>
 * @param menuCount 已授权菜单数，用于列表上快速判断"这个角色配了没"
 * @param dataScope 数据范围（ALL/CUSTOM/DEPT/DEPT_AND_CHILD/SELF）
 */
public record RoleDTO(
        Long id,
        Long tenantId,
        String roleName,
        String roleKey,
        Integer sort,
        String dataScope,
        Boolean builtin,
        String status,
        String remark,
        Integer userCount,
        Integer menuCount,
        List<Long> menuIds,
        List<Long> deptIds,
        Instant createTime
) {
}
