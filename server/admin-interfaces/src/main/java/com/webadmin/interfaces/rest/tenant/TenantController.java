package com.webadmin.interfaces.rest.tenant;

import com.webadmin.application.iam.TenantAppService;
import com.webadmin.application.iam.TenantProvisionAppService;
import com.webadmin.application.iam.command.CreateTenantCommand;
import com.webadmin.application.iam.command.RenewTenantCommand;
import com.webadmin.application.iam.dto.TenantUsageView;
import com.webadmin.application.iam.port.SubscriptionPlanRegistry;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.domain.iam.model.tenant.QuotaType;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.interfaces.rest.tenant.assembler.TenantAssembler;
import com.webadmin.interfaces.rest.tenant.request.CreateTenantRequest;
import com.webadmin.interfaces.rest.tenant.request.RenewTenantRequest;
import com.webadmin.interfaces.rest.tenant.request.TenantPageRequest;
import com.webadmin.interfaces.rest.tenant.response.TenantResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
    private final TenantProvisionAppService tenantProvisionAppService;
    private final SubscriptionPlanRegistry planRegistry;
    private final TenantAssembler tenantAssembler;

    // ==================================================================
    // 开通向导 / 套餐 / 用量（多租户治理）
    // ==================================================================

    /**
     * 一键开通：建租户 + 初始化租户管理员 + 激活，一个事务完成。
     *
     * <p>为什么不在前端把"创建租户 → 建角色 → 建用户 → 激活"串起来：
     * 那四步分属四个接口，中间任何一步失败都留下半成品
     * （最糟的是"PENDING 租户没有管理员"，谁也进不去也删不掉）。
     * 编排必须落在后端的一个事务里。
     */
    @Operation(operationId = "provisionTenant", summary = "一键开通租户",
            description = "开通向导：创建租户、初始化租户管理员（SUPER_ADMIN 角色与管理员账号，计入 USER 配额）"
                    + "并激活，全部在一个事务内完成。初始密码明文只在本次响应中返回，之后只能重置")
    @PreAuthorize("@ps.hasPermission('iam:tenant:create')")
    @PostMapping("/provision")
    public R<TenantProvisionResponse> provision(@Valid @RequestBody ProvisionTenantRequest request) {
        TenantProvisionAppService.ProvisionResult result = tenantProvisionAppService.provision(
                new TenantProvisionAppService.ProvisionCommand(
                        request.code(), request.name(), request.planCode(),
                        request.adminUsername(), request.adminPassword()));
        return R.ok(new TenantProvisionResponse(
                result.tenantId(), result.tenantCode(), result.planCode(),
                result.adminUsername(), result.initialPassword()));
    }

    @Operation(operationId = "listTenantPlans", summary = "查询可用套餐",
            description = "开通向导的套餐下拉与用量对比使用。配额上限来自套餐定义")
    @PreAuthorize("@ps.hasPermission('iam:tenant:query')")
    @GetMapping("/plans")
    public R<List<PlanResponse>> plans() {
        return R.ok(planRegistry.findAll().stream()
                .map(plan -> new PlanResponse(
                        plan.code(),
                        plan.name(),
                        plan.initialQuota().remaining(QuotaType.USER),
                        plan.initialQuota().remaining(QuotaType.STORAGE_BYTES),
                        plan.initialQuota().remaining(QuotaType.API_CALLS_PER_MONTH)))
                .toList());
    }

    @Operation(operationId = "getTenantUsage", summary = "租户用量（看板）",
            description = "各配额维度的总量/已用/剩余、实时用户数与有效状态。"
                    + "有效状态会把『ACTIVE 但已过有效期』直接判为 EXPIRED（脏读防护）")
    @PreAuthorize("@ps.hasPermission('iam:tenant:query')")
    @GetMapping("/{id}/usage")
    public R<TenantUsageResponse> usage(
            @Parameter(description = "租户 ID", required = true) @PathVariable Long id) {
        TenantUsageView view = tenantAppService.usage(TenantId.of(id));
        return R.ok(new TenantUsageResponse(
                view.tenantId(), view.tenantCode(), view.tenantName(),
                view.status(), view.effectiveStatus(), view.planCode(), view.planName(),
                view.expireTime(), view.expired(), view.liveUsers(),
                view.quota().stream()
                        .map(d -> new QuotaDimensionResponse(d.type(), d.label(), d.initial(), d.used(), d.remaining()))
                        .toList()));
    }

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

    // ==================================================================
    // Wire 契约
    // ==================================================================

    @Schema(description = "一键开通租户请求")
    public record ProvisionTenantRequest(
            @Schema(description = "租户编码（全局唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入租户编码")
            String code,

            @Schema(description = "租户名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入租户名称")
            String name,

            @Schema(description = "套餐编码（见 /plans）", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请选择套餐")
            String planCode,

            @Schema(description = "租户管理员账号（租户内唯一，不同租户可重名，如都用 admin）",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入管理员账号")
            String adminUsername,

            @Schema(description = "管理员初始密码；留空则使用平台初始密码")
            String adminPassword
    ) {
    }

    @Schema(description = "开通结果")
    public record TenantProvisionResponse(
            @Schema(description = "租户 ID") long tenantId,
            @Schema(description = "租户编码") String tenantCode,
            @Schema(description = "套餐编码") String planCode,
            @Schema(description = "管理员账号") String adminUsername,
            @Schema(description = "初始密码明文（只返回这一次）") String initialPassword) {
    }

    @Schema(description = "套餐")
    public record PlanResponse(
            @Schema(description = "套餐编码") String code,
            @Schema(description = "套餐名称") String name,
            @Schema(description = "用户数上限") long maxUsers,
            @Schema(description = "存储上限（字节）") long maxStorageBytes,
            @Schema(description = "月度 API 调用上限") long maxApiCalls) {
    }

    @Schema(description = "租户用量")
    public record TenantUsageResponse(
            @Schema(description = "租户 ID") long tenantId,
            @Schema(description = "租户编码") String tenantCode,
            @Schema(description = "租户名称") String tenantName,
            @Schema(description = "状态") String status,
            @Schema(description = "有效状态（ACTIVE 但已过期时为 EXPIRED）") String effectiveStatus,
            @Schema(description = "套餐编码") String planCode,
            @Schema(description = "套餐名称") String planName,
            @Schema(description = "到期时间") java.time.Instant expireTime,
            @Schema(description = "是否已过期") boolean expired,
            @Schema(description = "实时用户数") long liveUsers,
            @Schema(description = "各配额维度") List<QuotaDimensionResponse> quota) {
    }

    @Schema(description = "配额维度")
    public record QuotaDimensionResponse(
            @Schema(description = "维度标识") String type,
            @Schema(description = "维度名称") String label,
            @Schema(description = "总量") long initial,
            @Schema(description = "已用") long used,
            @Schema(description = "剩余") long remaining) {
    }
}
