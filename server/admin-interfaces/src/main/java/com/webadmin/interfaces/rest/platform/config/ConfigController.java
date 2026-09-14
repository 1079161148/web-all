package com.webadmin.interfaces.rest.platform.config;

import com.webadmin.application.platform.ConfigAppService;
import com.webadmin.application.platform.dto.ConfigDTO;
import com.webadmin.application.platform.port.ConfigPort;
import com.webadmin.application.platform.query.ConfigPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.iam.IamErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参数配置接口。
 *
 * <h3>{@code /values} 为什么只要求登录</h3>
 * 与字典同理：参数值是前端渲染与交互的基础数据（分页大小默认值、上传大小上限提示等）。
 * 若要求 {@code plt:config:query}，会出现"某角色能打开页面，但页面上所有
 * 由参数驱动的行为都取不到值"。参数里不应放敏感信息 —— 那是密钥管理的事，
 * 不是系统参数的事。
 */
@Tag(name = "参数配置", description = "系统参数的维护。支持平台默认 + 租户覆盖两级取值")
@RestController
@RequestMapping("/api/v1/platform/configs")
@RequiredArgsConstructor
@Validated
public class ConfigController {

    private final ConfigAppService configAppService;
    private final ConfigPort configPort;

    @Operation(operationId = "pageConfigs", summary = "分页查询参数")
    @PreAuthorize("@ps.hasPermission('plt:config:query')")
    @GetMapping
    public R<PageResult<ConfigResponse>> page(@ParameterObject ConfigPageQuery query) {
        return R.ok(configPort.page(query).map(ConfigResponse::from));
    }

    @Operation(operationId = "getConfig", summary = "查询参数详情")
    @PreAuthorize("@ps.hasPermission('plt:config:query')")
    @GetMapping("/{id}")
    public R<ConfigResponse> detail(@PathVariable Long id) {
        return R.ok(configPort.findById(id).map(ConfigResponse::from)
                .orElseThrow(() -> new BizException(
                        IamErrorCode.CONFIG_NOT_FOUND, "参数不存在")));
    }

    @Operation(operationId = "getConfigValues", summary = "批量取参数值",
            description = "一次查询返回多个键的值（租户覆盖优先）。"
                    + "页面初始化需要多个参数时用这个，避免多次往返（接口瀑布）")
    @GetMapping("/values")
    public R<Map<String, String>> values(@RequestParam List<String> keys) {
        return R.ok(configPort.findValuesByKeys(keys));
    }

    @Operation(operationId = "createConfig", summary = "新增参数",
            description = "写入落在当前租户。内置标记由迁移脚本设定，业务侧创建的参数不可为内置")
    @PreAuthorize("@ps.hasPermission('plt:config:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody ConfigRequest request) {
        return R.ok(configAppService.create(request.configName(), request.configKey(),
                request.configValue(), request.remark()));
    }

    @Operation(operationId = "updateConfig", summary = "修改参数")
    @PreAuthorize("@ps.hasPermission('plt:config:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ConfigRequest request) {
        configAppService.update(id, request.configName(), request.configKey(),
                request.configValue(), request.remark());
        return R.ok();
    }

    @Operation(operationId = "deleteConfig", summary = "删除参数",
            description = "系统内置参数不允许删除（应改值而非删行）")
    @PreAuthorize("@ps.hasPermission('plt:config:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        configAppService.delete(id);
        return R.ok();
    }

    // ==================================================================

    @Schema(description = "参数配置")
    public record ConfigResponse(
            @Schema(description = "参数 ID") Long id,
            @Schema(description = "参数名称") String configName,
            @Schema(description = "参数键") String configKey,
            @Schema(description = "参数值") String configValue,
            @Schema(description = "是否系统内置（内置参数不可删除）") Boolean builtin,
            @Schema(description = "备注") String remark,
            @Schema(description = "来源租户：0=平台默认，其余=本租户覆盖") Long sourceTenantId
    ) {
        public static ConfigResponse from(ConfigDTO dto) {
            return dto == null ? null : new ConfigResponse(dto.id(), dto.configName(),
                    dto.configKey(), dto.configValue(), dto.builtin(), dto.remark(),
                    dto.sourceTenantId());
        }
    }

    @Schema(description = "参数新增/修改请求")
    public record ConfigRequest(
            @Schema(description = "参数名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入参数名称")
            @Size(max = 128, message = "名称长度不能超过 128")
            String configName,

            @Schema(description = "参数键，形如 sys.user.init-password",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "sys.user.init-password")
            @NotBlank(message = "请输入参数键")
            @Size(max = 128, message = "参数键长度不能超过 128")
            @Pattern(regexp = "^[a-z][a-z0-9_.-]*$",
                    message = "参数键只能以小写字母开头，且仅含小写字母、数字、点、下划线与连字符")
            String configKey,

            @Schema(description = "参数值", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入参数值")
            @Size(max = 1024, message = "参数值长度不能超过 1024")
            String configValue,

            @Schema(description = "备注")
            String remark
    ) {
    }
}
