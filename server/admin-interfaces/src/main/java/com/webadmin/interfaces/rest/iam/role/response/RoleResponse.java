package com.webadmin.interfaces.rest.iam.role.response;

import com.webadmin.application.iam.dto.RoleDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 角色信息（对外契约）。 */
@Schema(description = "角色信息")
public record RoleResponse(

        @Schema(description = "角色 ID")
        Long id,

        @Schema(description = "角色名称")
        String roleName,

        @Schema(description = "角色标识（大写字母/数字/下划线，不带 ROLE_ 前缀）")
        String roleKey,

        @Schema(description = "显示顺序")
        Integer sort,

        @Schema(description = "数据范围：ALL/CUSTOM/DEPT/DEPT_AND_CHILD/SELF")
        String dataScope,

        @Schema(description = "是否内置角色。内置角色不允许改名、改标识、停用与删除")
        Boolean builtin,

        @Schema(description = "状态：ACTIVE/SUSPENDED")
        String status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "关联用户数。用于在删除前提示占用情况")
        Integer userCount,

        @Schema(description = "已授权菜单数")
        Integer menuCount,

        @Schema(description = "已授权的菜单 ID 列表（仅详情接口返回，用于权限树回显）")
        List<Long> menuIds,

        @Schema(description = "自定义数据范围的部门 ID 列表（仅详情接口返回）")
        List<Long> deptIds,

        @Schema(description = "创建时间（UTC）")
        Instant createTime
) {

    public static RoleResponse from(RoleDTO dto) {
        if (dto == null) {
            return null;
        }
        return new RoleResponse(
                dto.id(), dto.roleName(), dto.roleKey(), dto.sort(), dto.dataScope(),
                dto.builtin(), dto.status(), dto.remark(),
                dto.userCount(), dto.menuCount(), dto.menuIds(), dto.deptIds(),
                dto.createTime());
    }
}
