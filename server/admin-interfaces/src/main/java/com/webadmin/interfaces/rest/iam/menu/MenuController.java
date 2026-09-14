package com.webadmin.interfaces.rest.iam.menu;

import com.webadmin.application.iam.MenuAppService;
import com.webadmin.application.iam.dto.MenuDTO;
import com.webadmin.application.iam.port.MenuQueryPort;
import com.webadmin.common.api.R;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.iam.IamErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
 * 菜单管理接口。
 *
 * <h3>查询返回扁平结构（与 /auth/menus 一致）</h3>
 * 菜单总量在千级以内，一次全量返回 + 前端建树，比"按层级懒加载"更简单也更快。
 * 前端有两处需要菜单树：菜单管理页、角色的权限分配树 ——
 * 两者对"哪些节点可勾选"的规则不同（前者可勾全部，后者通常只勾 MENU/BUTTON），
 * 由前端按各自规则建树，比后端为两个场景各出一个树接口更灵活。
 *
 * <h3>菜单的 tenant_id 固定为 0（平台级共享）</h3>
 * 因此本模块的查询不受租户隔离影响（{@code iam_menu} 在租户拦截器的忽略名单里），
 * 但<b>写操作需要平台管理员的权限</b> —— 改菜单会影响所有租户。
 */
@Tag(name = "菜单管理", description = "菜单与权限点的维护（平台级，影响所有租户）")
@RestController
@RequestMapping("/api/v1/iam/menus")
@RequiredArgsConstructor
@Validated
public class MenuController {

    private final MenuAppService menuAppService;
    private final MenuQueryPort menuQueryPort;

    @Operation(operationId = "listMenus", summary = "查询全部菜单（扁平结构）",
            description = "含按钮权限点。前端按需建树：菜单管理页可勾全部，角色权限分配通常只勾 MENU/BUTTON")
    @PreAuthorize("@ps.hasPermission('iam:menu:query')")
    @GetMapping
    public R<List<MenuDTO>> list() {
        return R.ok(menuQueryPort.listAll());
    }

    @Operation(operationId = "getMenu", summary = "查询菜单详情")
    @PreAuthorize("@ps.hasPermission('iam:menu:query')")
    @GetMapping("/{id}")
    public R<MenuDTO> detail(@PathVariable Long id) {
        return R.ok(menuQueryPort.findById(id).orElseThrow(
                () -> new BizException(IamErrorCode.MENU_NOT_FOUND, "菜单不存在")));
    }

    @Operation(operationId = "createMenu", summary = "新增菜单",
            description = "菜单/目录必须配组件路径，按钮必须配权限码（由领域实体校验）")
    @PreAuthorize("@ps.hasPermission('iam:menu:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody MenuRequest request) {
        return R.ok(menuAppService.createMenu(
                request.parentId(), request.menuName(), request.menuType(), request.path(),
                request.component(), request.perms(), request.icon(), request.sort(),
                request.visible(), request.keepAlive(), request.alwaysShow()));
    }

    @Operation(operationId = "updateMenu", summary = "修改菜单",
            description = "不能把菜单挂到自己下面；校验规则与新增完全一致")
    @PreAuthorize("@ps.hasPermission('iam:menu:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody MenuRequest request) {
        menuAppService.updateMenu(id, request.parentId(), request.menuName(), request.menuType(),
                request.path(), request.component(), request.perms(), request.icon(),
                request.sort(), request.visible(), request.keepAlive(), request.alwaysShow());
        return R.ok();
    }

    @Operation(operationId = "deleteMenu", summary = "删除菜单",
            description = "存在子节点时拒绝删除。不做级联删除：一次误操作会连带删掉整个子树的权限点")
    @PreAuthorize("@ps.hasPermission('iam:menu:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        menuAppService.deleteMenu(id);
        return R.ok();
    }

    // ==================================================================

    @Schema(description = "菜单新增/修改请求（两种操作用同一套校验规则，避免规则写两份而漂移）")
    public record MenuRequest(
            @Schema(description = "父菜单 ID，0 表示根")
            Long parentId,

            @Schema(description = "菜单名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入菜单名称")
            @Size(max = 64, message = "菜单名称长度不能超过 64")
            String menuName,

            @Schema(description = "类型：DIR=目录 MENU=菜单 BUTTON=按钮",
                    requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DIR", "MENU", "BUTTON"})
            @NotBlank(message = "请选择菜单类型")
            String menuType,

            @Schema(description = "路由地址（DIR/MENU 必填）。DIR 用 /system 这类绝对路径，MENU 用 user 这类相对片段")
            String path,

            @Schema(description = "组件路径（仅 MENU 必填），如 iam/user/index。前端用 glob 查表映射，不硬编码")
            String component,

            @Schema(description = "权限码（仅 BUTTON 必填），格式 {context}:{resource}:{action}，如 iam:user:add")
            String perms,

            @Schema(description = "图标名")
            String icon,

            @Schema(description = "显示顺序", defaultValue = "0")
            Integer sort,

            @Schema(description = "是否显示", defaultValue = "true")
            Boolean visible,

            @Schema(description = "是否缓存页面", defaultValue = "true")
            Boolean keepAlive,

            @Schema(description = "只有一个子路由时是否仍显示父级", defaultValue = "false")
            Boolean alwaysShow
    ) {
    }
}
