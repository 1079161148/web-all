package com.webadmin.interfaces.rest.iam.role.response;

import com.webadmin.application.iam.dto.RoleSimulationView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 数据权限模拟结果（Wire 契约）。
 *
 * <h3>为什么不直接返回应用层 DTO，而要再映射一层</h3>
 * 与项目其余接口一致：应用层 DTO 的演进（拆聚合、改命名）不应直接改变对外契约。
 * 契约是前端与第三方依赖的东西，改动必须是<b>有意的</b>。
 *
 * <p>本地化文案（"全部数据"/"本部门及以下"）由前端字典 {@code sys_data_scope} 提供，
 * 后端只返回枚举名 —— 否则同一个概念会有两份中文，迟早不一致。
 */
@Schema(description = "数据权限模拟结果")
public record RoleSimulationResponse(
        @Schema(description = "角色 ID") long roleId,
        @Schema(description = "角色标识") String roleKey,
        @Schema(description = "角色名称") String roleName,
        @Schema(description = "被模拟用户 ID") long userId,
        @Schema(description = "被模拟用户账号") String username,
        @Schema(description = "被模拟用户昵称") String nickname,
        @Schema(description = "数据范围：ALL/CUSTOM/DEPT/DEPT_AND_CHILD/SELF") String dataScope,
        @Schema(description = "被模拟用户所属部门 ID（未分配部门时为空）") Long deptId,
        @Schema(description = "被模拟用户所属部门名（未分配部门时为空）") String deptName,
        @Schema(description = "生效部门；ALL/SELF 范围为空列表（语义正确，不是没查到）")
        List<DeptItem> depts,
        @Schema(description = "各资源可见条数") List<ResourceItem> resources) {

    @Schema(description = "生效部门")
    public record DeptItem(
            @Schema(description = "部门 ID") long deptId,
            @Schema(description = "部门名称") String deptName) {
    }

    @Schema(description = "资源可见条数")
    public record ResourceItem(
            @Schema(description = "资源标识（表名，如 iam_user）") String resource,
            @Schema(description = "资源名称") String label,
            @Schema(description = "可见条数") long visible) {
    }

    public static RoleSimulationResponse from(RoleSimulationView view) {
        return new RoleSimulationResponse(
                view.roleId(),
                view.roleKey(),
                view.roleName(),
                view.userId(),
                view.username(),
                view.nickname(),
                view.dataScope(),
                view.deptId(),
                view.deptName(),
                view.depts().stream()
                        .map(d -> new DeptItem(d.deptId(), d.deptName()))
                        .toList(),
                view.resources().stream()
                        .map(r -> new ResourceItem(r.resource(), r.label(), r.visible()))
                        .toList());
    }
}
