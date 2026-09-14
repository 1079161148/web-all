package com.webadmin.interfaces.rest.tenant;

import com.webadmin.application.iam.TenantAppService;
import com.webadmin.application.iam.command.CreateTenantCommand;
import com.webadmin.application.iam.command.RenewTenantCommand;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.interfaces.rest.tenant.assembler.TenantAssembler;
import com.webadmin.interfaces.rest.tenant.request.CreateTenantRequest;
import com.webadmin.interfaces.rest.tenant.request.RenewTenantRequest;
import com.webadmin.interfaces.rest.tenant.request.TenantPageRequest;
import com.webadmin.interfaces.rest.tenant.response.TenantResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理接口（平台级功能）。
 *
 * <h3>权限注解已全部落地（P1 权限闭环）</h3>
 * 与早期版本的区别：那时 {@code @PreAuthorize} 只是一段 TODO 注释，
 * 意味着<b>接口完全裸奔</b> —— 任何人只要过了登录就能关停任意租户。
 * 现在每个方法都有真实注解，且被 {@code SecurityConfig} 的
 * {@code @EnableMethodSecurity} 激活。
 *
 * <p>权限码来自 {@code iam_menu.perms}（见 Flyway V1.0.4 的种子数据），
 * 与前端按钮权限指令使用<b>同一套码</b> —— 避免"后端要求 A、前端配置 B"这种
 * 永远不会通过的组合。
 *
 * <h3>三层防护</h3>
 * <ol>
 *   <li>{@code SecurityConfig} 的 {@code anyRequest().authenticated()} —— 未登录直接 401</li>
 *   <li>本类的 {@code @PreAuthorize} —— 已登录但无权限码返回 403</li>
 *   <li>领域层的不变量 —— 即使权限通过，非法状态流转仍会被聚合拒绝</li>
 * </ol>
 * 三层各管一件事，互不替代。特别是第 3 层：它保护的是<b>业务正确性</b>，
 * 而前两层保护的是<b>访问合法性</b>。
 */
@Tag(name = "租户管理", description = "平台级多租户生命周期与配额管理")
@RestController
@RequestMapping("/api/v1/iam/tenants")
@RequiredArgsConstructor
@Validated
public class TenantController {

    private final TenantAppService tenantAppService;
    private final TenantAssembler tenantAssembler;

    @Operation(operationId = "pageTenants", summary = "分页查询租户",
            description = "支持按编码/名称模糊匹配、状态与套餐精确匹配、创建时间区间过滤")
    @PreAuthorize("@ps.hasPermission('iam:tenant:query')")
    @GetMapping
    public R<PageResult<TenantResponse>> page(@ParameterObject @Valid TenantPageRequest request) {
        return R.ok(tenantAssembler.toResponsePage(
                tenantAppService.page(tenantAssembler.toQuery(request))));
    }

    @Operation(operationId = "getTenant", summary = "查询租户详情")
    @PreAuthorize("@ps.hasPermission('iam:tenant:query')")
    @GetMapping("/{id}")
    public R<TenantResponse> detail(
            @Parameter(description = "租户 ID", required = true) @PathVariable Long id) {
        return R.ok(tenantAssembler.toResponse(tenantAppService.getById(TenantId.of(id))));
    }

    @Operation(operationId = "createTenant", summary = "创建租户",
            description = "新建租户初始为 PENDING 状态，需调用激活接口后才可使用")
    @PreAuthorize("@ps.hasPermission('iam:tenant:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantId tenantId = tenantAppService.createTenant(new CreateTenantCommand(
                request.code(), request.name(), request.planCode(), request.remark()));
        return R.ok(tenantId.value());
    }

    @Operation(operationId = "activateTenant", summary = "激活租户",
            description = "PENDING / SUSPENDED / EXPIRED → ACTIVE")
    @PreAuthorize("@ps.hasPermission('iam:tenant:update')")
    @PutMapping("/{id}/activate")
    public R<Void> activate(@PathVariable Long id) {
        tenantAppService.activate(TenantId.of(id));
        return R.ok();
    }

    @Operation(operationId = "suspendTenant", summary = "暂停租户",
            description = "数据保留但拒绝访问；会清理权限缓存并强制在线用户下线")
    @PreAuthorize("@ps.hasPermission('iam:tenant:update')")
    @PutMapping("/{id}/suspend")
    public R<Void> suspend(
            @PathVariable Long id,
            @Parameter(description = "暂停原因", required = true)
            @RequestParam @NotBlank(message = "暂停原因不能为空") String reason) {
        tenantAppService.suspend(TenantId.of(id), reason);
        return R.ok();
    }

    @Operation(operationId = "renewTenant", summary = "租户续期",
            description = "延长到期时间、可切换套餐并重置配额；若已过期会自动恢复为 ACTIVE")
    @PreAuthorize("@ps.hasPermission('iam:tenant:update')")
    @PutMapping("/{id}/renew")
    public R<Void> renew(@PathVariable Long id, @Valid @RequestBody RenewTenantRequest request) {
        tenantAppService.renew(new RenewTenantCommand(
                TenantId.of(id), request.months(), request.planCode()));
        return R.ok();
    }

    @Operation(operationId = "renameTenant", summary = "重命名租户")
    @PreAuthorize("@ps.hasPermission('iam:tenant:update')")
    @PutMapping("/{id}/name")
    public R<Void> rename(
            @PathVariable Long id,
            @RequestParam @NotBlank(message = "租户名称不能为空") String name) {
        tenantAppService.rename(TenantId.of(id), name);
        return R.ok();
    }

    @Operation(operationId = "closeTenant", summary = "关闭租户",
            description = "终态，不可恢复。租户不做物理删除，以保证历史数据的租户归属不悬空")
    @PreAuthorize("@ps.hasPermission('iam:tenant:close')")
    @PutMapping("/{id}/close")
    public R<Void> close(
            @PathVariable Long id,
            @RequestParam @NotBlank(message = "关闭原因不能为空") String reason) {
        tenantAppService.close(TenantId.of(id), reason);
        return R.ok();
    }
}
