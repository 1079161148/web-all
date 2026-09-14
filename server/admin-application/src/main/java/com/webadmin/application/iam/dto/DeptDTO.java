package com.webadmin.application.iam.dto;

import java.time.Instant;

/**
 * 部门读模型。
 *
 * <h3>为什么返回扁平结构而不是树</h3>
 * 与菜单一致（见 {@code AuthAppService.currentUserMenus} 的说明）：
 * 树结构把"建树规则"固化进了接口，而扁平结构能让同一个数据源服务多种视图 ——
 * 部门管理页要树、用户表单的部门选择器要树、数据范围配置要"可选部门树"、
 * 面包屑要"从根到当前节点的路径"。这些视图的建树方式并不完全相同。
 *
 * <p>{@code ancestors} 一并返回，前端可以据此做"展开到指定节点"而无需递归查询。
 */
public record DeptDTO(
        Long id,
        Long tenantId,
        Long parentId,
        /** 祖级物化路径，如 {@code 0,100,205}。前端可用它直接判断层级与祖先关系。 */
        String ancestors,
        String deptName,
        Integer sort,
        Long leaderUserId,
        String phone,
        String email,
        String status,
        String remark,
        Instant createTime
) {
}
