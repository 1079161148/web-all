package com.webadmin.interfaces.rest.iam.user;

import com.webadmin.application.iam.UserAppService;
import com.webadmin.application.iam.command.CreateUserCommand;
import com.webadmin.application.iam.command.UpdateUserCommand;
import com.webadmin.application.iam.port.UserQueryPort;
import com.webadmin.application.iam.query.UserPageQuery;
import com.webadmin.interfaces.rest.iam.user.response.UserResponse;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.common.error.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * 用户管理接口。
 *
 * <h3>关于 wire 契约（请求/响应对象）写在本文件内</h3>
 * 用户模块的四个契约类型只在<b>本控制器</b>使用。把它们各自拆成独立文件，
 * 收益只是"遵守一个类型一个文件"的形式，代价是阅读一个接口要跳 4 个文件。
 *
 * <p>这里选择了<b>契约贴着端点</b>：打开本文件即可看到该模块完整的对外形态。
 * 判断依据是「复用范围」—— 只有一处使用的类型不需要独立文件；
 * 一旦出现第二个消费方（如导出接口复用同一个查询请求），就应立刻抽出去。
 *
 * <h3>每个写接口都独立声明权限码</h3>
 * 没有把"重置密码"和"修改资料"合并成一个 update。它们的风险等级完全不同：
 * 能改昵称不代表能重置别人密码。<b>把不同风险的操作合并，等于把最弱的那一环
 * 当成整体权限 —— 这是权限设计里最常见的漏洞。</b>
 */
@Tag(name = "用户管理", description = "用户的增删改查、角色分配、密码重置与启停")
@RestController
@RequestMapping("/api/v1/iam/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserAppService userAppService;
    private final UserQueryPort userQueryPort;

    // ==================================================================
    // 查询
    // ==================================================================

    @Operation(operationId = "pageUsers", summary = "分页查询用户",
            description = "自动施加租户隔离与部门数据权限；支持账号/昵称/手机号模糊匹配、部门（可含下级）与角色筛选")
    @PreAuthorize("@ps.hasPermission('iam:user:query')")
    @GetMapping
    public R<PageResult<UserResponse>> page(@ParameterObject UserPageQuery query) {
        // 应用层读模型 → 接口层契约。转换集中在 PageResult.map 里，
        // 分页元信息（total/page/size）不由各模块各自搬运 —— 抄错 total 会让分页静默错乱
        return R.ok(userQueryPort.page(query).map(UserResponse::from));
    }

    @Operation(operationId = "getUser", summary = "查询用户详情")
    @PreAuthorize("@ps.hasPermission('iam:user:query')")
    @GetMapping("/{id}")
    public R<UserResponse> detail(@PathVariable Long id) {
        return R.ok(userQueryPort.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new BizException(
                        com.webadmin.domain.iam.IamErrorCode.USER_NOT_FOUND, "用户不存在")));
    }

    // ==================================================================
    // 新增 / 修改 / 删除
    // ==================================================================

    @Operation(operationId = "createUser", summary = "新增用户",
            description = "不传密码时使用平台初始密码；密码强度按平台策略校验")
    @PreAuthorize("@ps.hasPermission('iam:user:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody CreateUserRequest request) {
        return R.ok(userAppService.createUser(new CreateUserCommand(
                request.username(), request.nickname(), request.password(),
                request.email(), request.phone(), request.deptId(),
                request.sex(), request.roleIds())));
    }

    @Operation(operationId = "updateUser", summary = "修改用户",
            description = "仅修改基础资料与角色。密码与状态有独立接口（便于分别授权，也避免漏传字段导致误改）")
    @PreAuthorize("@ps.hasPermission('iam:user:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        userAppService.updateUser(id, new UpdateUserCommand(
                request.nickname(), request.email(), request.phone(),
                request.deptId(), request.sex(), request.avatar(), request.roleIds()));
        return R.ok();
    }

    @Operation(operationId = "deleteUser", summary = "删除用户",
            description = "逻辑删除。不能删除自己，也不能删除内置超管账号")
    @PreAuthorize("@ps.hasPermission('iam:user:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userAppService.deleteUser(id);
        return R.ok();
    }

    // ==================================================================
    // 密码
    // ==================================================================

    @Operation(operationId = "resetUserPassword", summary = "重置用户密码",
            description = "不传新密码时重置为平台初始密码。重置后该用户的权限缓存会被清除")
    @PreAuthorize("@ps.hasPermission('iam:user:reset-password')")
    @PutMapping("/{id}/password")
    public R<Void> resetPassword(@PathVariable Long id,
                                 @RequestBody(required = false) ResetPasswordRequest request) {
        userAppService.resetPassword(id, request == null ? null : request.password());
        return R.ok();
    }

    // ==================================================================
    // 状态
    // ==================================================================

    @Operation(operationId = "changeUserStatus", summary = "启用/停用用户",
            description = "仅支持 ACTIVE 与 SUSPENDED。锁定状态由登录失败自动触发，不提供手工入口")
    @PreAuthorize("@ps.hasPermission('iam:user:update')")
    @PutMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id,
                                @Valid @RequestBody ChangeStatusRequest request) {
        userAppService.changeStatus(id, request.status(), request.reason());
        return R.ok();
    }

    @Operation(operationId = "unlockUser", summary = "解锁用户",
            description = "解除因连续登录失败导致的锁定，同时清零失败计数")
    @PreAuthorize("@ps.hasPermission('iam:user:update')")
    @PutMapping("/{id}/unlock")
    public R<Void> unlock(@PathVariable Long id) {
        userAppService.unlock(id);
        return R.ok();
    }

    // ==================================================================
    // 角色分配
    // ==================================================================

    @Operation(operationId = "assignUserRoles", summary = "分配用户角色",
            description = "全量覆盖语义：提交的是最终角色集合，不是增量")
    @PreAuthorize("@ps.hasPermission('iam:user:update')")
    @PutMapping("/{id}/roles")
    public R<Void> assignRoles(@PathVariable Long id,
                               @RequestBody AssignRolesRequest request) {
        userAppService.assignRoles(id, request.roleIds());
        return R.ok();
    }

    // ==================================================================
    // Wire 契约
    // ==================================================================

    @Schema(description = "新增用户请求")
    public record CreateUserRequest(
            @Schema(description = "登录账号，4~64 位字母/数字/下划线，租户内唯一", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入登录账号")
            @Size(max = 64, message = "账号长度不能超过 64")
            String username,

            @Schema(description = "昵称/姓名", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入昵称")
            @Size(max = 64, message = "昵称长度不能超过 64")
            String nickname,

            @Schema(description = "密码。留空则使用平台初始密码")
            String password,

            @Schema(description = "邮箱")
            String email,

            @Schema(description = "手机号")
            String phone,

            @Schema(description = "所属部门 ID")
            Long deptId,

            @Schema(description = "性别：0=男 1=女 2=未知", defaultValue = "2")
            Integer sex,

            @Schema(description = "初始角色 ID 集合")
            Set<Long> roleIds
    ) {
    }

    @Schema(description = "修改用户请求")
    public record UpdateUserRequest(
            @Schema(description = "昵称/姓名", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入昵称")
            @Size(max = 64, message = "昵称长度不能超过 64")
            String nickname,

            @Schema(description = "邮箱")
            String email,

            @Schema(description = "手机号")
            String phone,

            @Schema(description = "所属部门 ID")
            Long deptId,

            @Schema(description = "性别：0=男 1=女 2=未知")
            Integer sex,

            @Schema(description = "头像 URL")
            String avatar,

            @Schema(description = "角色 ID 集合（全量覆盖）")
            Set<Long> roleIds
    ) {
    }

    @Schema(description = "重置密码请求")
    public record ResetPasswordRequest(
            @Schema(description = "新密码。留空则重置为平台初始密码")
            String password
    ) {
    }

    @Schema(description = "变更用户状态请求")
    public record ChangeStatusRequest(
            @Schema(description = "目标状态：ACTIVE=启用，SUSPENDED=停用", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请选择目标状态")
            String status,

            @Schema(description = "变更原因。会写入领域事件，便于事后追溯", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请填写变更原因")
            String reason
    ) {
    }

    @Schema(description = "分配角色请求")
    public record AssignRolesRequest(
            @Schema(description = "角色 ID 集合（全量覆盖，传空集合表示清空全部角色）")
            @Parameter(description = "角色 ID 集合")
            Set<Long> roleIds
    ) {
    }
}
