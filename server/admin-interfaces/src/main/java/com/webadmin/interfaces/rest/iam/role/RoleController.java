package com.webadmin.interfaces.rest.iam.role;

import com.webadmin.application.iam.RoleAppService;
import com.webadmin.application.iam.port.RoleQueryPort;
import com.webadmin.application.iam.query.RolePageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.interfaces.rest.iam.role.response.RoleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口。
 *
 * <h3>权限分配是独立端点，不混进"修改角色"</h3>
 * 修改角色名称与"给它配上所有菜单权限"是完全不同的风险级别 ——
 * 后者等于授予他人访问系统的能力。合并成一个接口就无法分别授权，
 * 只能给"能改角色名的人"以"能授予任意权限"的能力。
 */
@Tag(name = "角色管理", description = "角色的增删改查、菜单权限与数据范围分配")
@RestController
@RequestMapping("/api/v1/iam/roles")
@RequiredArgsConstructor
@Validated
public class RoleController {

    private final RoleAppService roleAppService;
    private final RoleQueryPort roleQueryPort;

    // ==================================================================
    // 查询
    // ==================================================================

    @Operation(operationId = "pageRoles", summary = "分页查询角色",
            description = "返回关联用户数与已授权菜单数，便于在删除前判断占用情况")
    @PreAuthorize("@ps.hasPermission('iam:role:query')")
    @GetMapping
    public R<PageResult<RoleResponse>> page(@ParameterObject RolePageQuery query) {
        return R.ok(roleQueryPort.page(query).map(RoleResponse::from));
    }

    @Operation(operationId = "listUsableRoles", summary = "查询全部可用角色",
            description = "供角色下拉与用户筛选使用。刻意不分页：角色数量在几十个量级，分页只会让下拉更难用")
    @PreAuthorize("@ps.hasPermission('iam:role:query')")
    @GetMapping("/usable")
    public R<List<RoleResponse>> usable() {
        return R.ok(roleQueryPort.findUsable().stream().map(RoleResponse::from).toList());
    }

    @Operation(operationId = "getRole", summary = "查询角色详情",
            description = "含已授权菜单 ID 与自定义数据范围部门 ID，用于权限分配界面回显")
    @PreAuthorize("@ps.hasPermission('iam:role:query')")
    @GetMapping("/{id}")
    public R<RoleResponse> detail(@PathVariable Long id) {
        return R.ok(roleQueryPort.findById(id)
                .map(RoleResponse::from)
                .orElseThrow(() -> new BizException(IamErrorCode.ROLE_NOT_FOUND, "角色不存在")));
    }

    // ==================================================================
    // 增删改
    // ==================================================================

    @Operation(operationId = "createRole", summary = "新增角色",
            description = "角色标识需为大写字母/数字/下划线，不要带 ROLE_ 前缀（Spring Security 的 hasRole 会自动添加）")
    @PreAuthorize("@ps.hasPermission('iam:role:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody CreateRoleRequest request) {
        return R.ok(roleAppService.createRole(new RoleAppService.CreateRoleCommand(
                request.roleKey(), request.roleName(), request.sort(),
                request.dataScope(), request.remark())));
    }

    @Operation(operationId = "updateRole", summary = "修改角色基础信息",
            description = "仅名称与排序。标识与内置标记不可改（由聚合裁决）")
    @PreAuthorize("@ps.hasPermission('iam:role:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        roleAppService.updateRole(id, request.roleName(), request.sort());
        return R.ok();
    }

    @Operation(operationId = "deleteRole", summary = "删除角色",
            description = "内置角色不可删除；仍有用户关联时会被拒绝并提示占用数量")
    @PreAuthorize("@ps.hasPermission('iam:role:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        roleAppService.deleteRole(id);
        return R.ok();
    }

    // ==================================================================
    // 权限与状态
    // ==================================================================

    @Operation(operationId = "assignRolePermissions", summary = "分配角色权限",
            description = "全量覆盖菜单权限、数据范围与自定义部门。变更后会自动刷新该角色下所有用户的权限缓存")
    @PreAuthorize("@ps.hasPermission('iam:role:assign')")
    @PutMapping("/{id}/permissions")
    public R<Void> assignPermissions(@PathVariable Long id,
                                     @Valid @RequestBody AssignPermissionsRequest request) {
        roleAppService.assignPermissions(id, request.menuIds(),
                request.dataScope(), request.deptIds());
        return R.ok();
    }

    @Operation(operationId = "changeRoleStatus", summary = "启用/停用角色",
            description = "停用后该角色不参与鉴权，且其下用户的权限缓存会被刷新")
    @PreAuthorize("@ps.hasPermission('iam:role:update')")
    @PutMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id,
                                @Valid @RequestBody ChangeRoleStatusRequest request) {
        roleAppService.changeStatus(id, request.status());
        return R.ok();
    }

    // ==================================================================
    // Wire 契约
    // ==================================================================

    @Schema(description = "新增角色请求")
    public record CreateRoleRequest(
            @Schema(description = "角色标识，如 AUDITOR。3~64 位大写字母/数字/下划线，不要带 ROLE_ 前缀",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "AUDITOR")
            @NotBlank(message = "请输入角色标识")
            @Size(max = 64, message = "角色标识长度不能超过 64")
            String roleKey,

            @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "审计员")
            @NotBlank(message = "请输入角色名称")
            @Size(max = 64, message = "角色名称长度不能超过 64")
            String roleName,

            @Schema(description = "显示顺序", defaultValue = "0")
            Integer sort,

            @Schema(description = "数据范围：ALL/CUSTOM/DEPT/DEPT_AND_CHILD/SELF", defaultValue = "SELF")
            String dataScope,

            @Schema(description = "备注")
            String remark
    ) {
    }

    @Schema(description = "修改角色请求")
    public record UpdateRoleRequest(
            @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入角色名称")
            @Size(max = 64, message = "角色名称长度不能超过 64")
            String roleName,

            @Schema(description = "显示顺序")
            Integer sort
    ) {
    }

    @Schema(description = "分配角色权限请求")
    public record AssignPermissionsRequest(
            @Schema(description = "菜单 ID 集合（全量覆盖）")
            Set<Long> menuIds,

            @Schema(description = "数据范围。选 CUSTOM 时必须至少填一个部门，否则会看不到任何数据",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请选择数据范围")
            String dataScope,

            @Schema(description = "自定义数据范围的部门 ID 集合（仅 dataScope=CUSTOM 时有意义）")
            Set<Long> deptIds
    ) {
    }

    @Schema(description = "变更角色状态请求")
    public record ChangeRoleStatusRequest(
            @Schema(description = "目标状态：ACTIVE=启用，SUSPENDED=停用", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请选择目标状态")
            String status
    ) {
    }
}
