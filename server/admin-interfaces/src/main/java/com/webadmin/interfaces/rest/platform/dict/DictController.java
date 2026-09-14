package com.webadmin.interfaces.rest.platform.dict;

import com.webadmin.application.platform.DictAppService;
import com.webadmin.application.platform.dto.DictDataDTO;
import com.webadmin.application.platform.dto.DictTypeDTO;
import com.webadmin.application.platform.port.DictQueryPort;
import com.webadmin.application.platform.query.DictDataPageQuery;
import com.webadmin.application.platform.query.DictTypePageQuery;
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
 * 字典管理接口。
 *
 * <h3>{@code /data/type/{dictType}} 是唯一「只要求登录、不要求权限码」的接口</h3>
 * 它是前端 {@code useDict} 的唯一入口，几乎每个页面都会用到。
 * 若也要求 {@code plt:dict:query}，会产生一个很隐蔽的问题：
 * <b>某个角色被授予了"查看用户列表"的权限，但没有字典权限 →
 * 用户列表页的性别/状态下拉框全部空白</b>，而页面本身是能打开的。
 * 这种"页面能开、控件是空的"极难被归因到字典权限上。
 *
 * <p>因此这里的判定是：<b>字典是页面渲染的基础设施数据，不属于业务数据</b>。
 * 它只要求身份认证（登录即可），并且只返回 {@code status=ACTIVE} 的项。
 */
@Tag(name = "字典管理", description = "字典类型与字典项的维护。支持平台默认 + 租户覆盖两级取值")
@RestController
@RequestMapping("/api/v1/platform/dict")
@RequiredArgsConstructor
@Validated
public class DictController {

    private final DictAppService dictAppService;
    private final DictQueryPort dictQueryPort;

    // ==================================================================
    // 字典类型
    // ==================================================================

    @Operation(operationId = "pageDictTypes", summary = "分页查询字典类型")
    @PreAuthorize("@ps.hasPermission('plt:dict:query')")
    @GetMapping("/types")
    public R<PageResult<DictTypeResponse>> pageTypes(@ParameterObject DictTypePageQuery query) {
        return R.ok(dictQueryPort.pageTypes(query).map(DictTypeResponse::from));
    }

    @Operation(operationId = "getDictType", summary = "查询字典类型详情")
    @PreAuthorize("@ps.hasPermission('plt:dict:query')")
    @GetMapping("/types/{id}")
    public R<DictTypeResponse> getType(@PathVariable Long id) {
        return R.ok(dictQueryPort.findTypeById(id).map(DictTypeResponse::from)
                .orElseThrow(() -> new BizException(
                        IamErrorCode.DICT_TYPE_NOT_FOUND, "字典类型不存在")));
    }

    @Operation(operationId = "createDictType", summary = "新增字典类型",
            description = "写入落在当前租户（租户级覆盖）。平台默认值来自迁移脚本，不由此接口修改")
    @PreAuthorize("@ps.hasPermission('plt:dict:create')")
    @PostMapping("/types")
    public R<Long> createType(@Valid @RequestBody DictTypeRequest request) {
        return R.ok(dictAppService.createType(
                request.dictName(), request.dictType(), request.status(), request.remark()));
    }

    @Operation(operationId = "updateDictType", summary = "修改字典类型",
            description = "⚠️ 修改类型编码不会自动更新其下字典项的 dictType 字段，需要一并修改")
    @PreAuthorize("@ps.hasPermission('plt:dict:update')")
    @PutMapping("/types/{id}")
    public R<Void> updateType(@PathVariable Long id, @Valid @RequestBody DictTypeRequest request) {
        dictAppService.updateType(id, request.dictName(), request.dictType(),
                request.status(), request.remark());
        return R.ok();
    }

    @Operation(operationId = "deleteDictType", summary = "删除字典类型",
            description = "类型下仍有字典项时拒绝删除")
    @PreAuthorize("@ps.hasPermission('plt:dict:delete')")
    @DeleteMapping("/types/{id}")
    public R<Void> deleteType(@PathVariable Long id) {
        dictAppService.deleteType(id);
        return R.ok();
    }

    // ==================================================================
    // 字典项
    // ==================================================================

    @Operation(operationId = "pageDictData", summary = "分页查询字典项")
    @PreAuthorize("@ps.hasPermission('plt:dict:query')")
    @GetMapping("/data")
    public R<PageResult<DictDataResponse>> pageData(@ParameterObject DictDataPageQuery query) {
        return R.ok(dictQueryPort.pageData(query).map(DictDataResponse::from));
    }

    @Operation(operationId = "listDictDataByType", summary = "按类型取字典项（前端 useDict 入口）",
            description = "仅要求登录、不要求权限码：字典是页面渲染的基础数据。"
                    + "合并平台默认与租户覆盖，同键值以租户级为准，按 sort 升序")
    @GetMapping("/data/type/{dictType}")
    public R<List<DictDataResponse>> listByType(@PathVariable String dictType) {
        return R.ok(dictQueryPort.listByType(dictType).stream()
                .map(DictDataResponse::from).toList());
    }

    @Operation(operationId = "getDictData", summary = "查询字典项详情")
    @PreAuthorize("@ps.hasPermission('plt:dict:query')")
    @GetMapping("/data/{id}")
    public R<DictDataResponse> getData(@PathVariable Long id) {
        return R.ok(dictQueryPort.findDataById(id).map(DictDataResponse::from)
                .orElseThrow(() -> new BizException(
                        IamErrorCode.DICT_DATA_NOT_FOUND, "字典项不存在")));
    }

    @Operation(operationId = "createDictData", summary = "新增字典项")
    @PreAuthorize("@ps.hasPermission('plt:dict:create')")
    @PostMapping("/data")
    public R<Long> createData(@Valid @RequestBody DictDataRequest request) {
        return R.ok(dictAppService.createData(request.dictType(), request.dictLabel(),
                request.dictValue(), request.sort(), request.cssClass(), request.listClass(),
                request.isDefault(), request.status(), request.remark()));
    }

    @Operation(operationId = "updateDictData", summary = "修改字典项")
    @PreAuthorize("@ps.hasPermission('plt:dict:update')")
    @PutMapping("/data/{id}")
    public R<Void> updateData(@PathVariable Long id, @Valid @RequestBody DictDataRequest request) {
        dictAppService.updateData(id, request.dictType(), request.dictLabel(),
                request.dictValue(), request.sort(), request.cssClass(), request.listClass(),
                request.isDefault(), request.status(), request.remark());
        return R.ok();
    }

    @Operation(operationId = "deleteDictData", summary = "删除字典项")
    @PreAuthorize("@ps.hasPermission('plt:dict:delete')")
    @DeleteMapping("/data/{id}")
    public R<Void> deleteData(@PathVariable Long id) {
        dictAppService.deleteData(id);
        return R.ok();
    }

    // ==================================================================
    // Wire 契约
    // ==================================================================

    @Schema(description = "字典类型")
    public record DictTypeResponse(
            @Schema(description = "字典类型 ID") Long id,
            @Schema(description = "字典名称") String dictName,
            @Schema(description = "字典类型编码") String dictType,
            @Schema(description = "状态：ACTIVE/DISABLED") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "来源租户：0=平台默认，其余=本租户覆盖") Long sourceTenantId
    ) {
        public static DictTypeResponse from(DictTypeDTO dto) {
            return dto == null ? null : new DictTypeResponse(dto.id(), dto.dictName(),
                    dto.dictType(), dto.status(), dto.remark(), dto.sourceTenantId());
        }
    }

    @Schema(description = "字典项")
    public record DictDataResponse(
            @Schema(description = "字典项 ID") Long id,
            @Schema(description = "所属字典类型编码") String dictType,
            @Schema(description = "展示值") String dictLabel,
            @Schema(description = "存储值") String dictValue,
            @Schema(description = "显示顺序") Integer sort,
            @Schema(description = "标签样式：success/warning/error/info/default") String cssClass,
            @Schema(description = "表格回显样式") String listClass,
            @Schema(description = "是否默认选中") Boolean isDefault,
            @Schema(description = "状态：ACTIVE/DISABLED") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "来源租户：0=平台默认，其余=本租户覆盖") Long sourceTenantId
    ) {
        public static DictDataResponse from(DictDataDTO dto) {
            return dto == null ? null : new DictDataResponse(dto.id(), dto.dictType(),
                    dto.dictLabel(), dto.dictValue(), dto.sort(), dto.cssClass(),
                    dto.listClass(), dto.isDefault(), dto.status(), dto.remark(),
                    dto.sourceTenantId());
        }
    }

    @Schema(description = "字典类型新增/修改请求")
    public record DictTypeRequest(
            @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入字典名称")
            @Size(max = 64, message = "字典名称长度不能超过 64")
            String dictName,

            @Schema(description = "字典类型编码，小写字母/数字/下划线，如 sys_user_sex",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_user_sex")
            @NotBlank(message = "请输入字典类型编码")
            @Size(max = 64, message = "编码长度不能超过 64")
            @Pattern(regexp = "^[a-z][a-z0-9_]*$",
                    message = "编码只能以小写字母开头，且仅含小写字母、数字与下划线")
            String dictType,

            @Schema(description = "状态：ACTIVE/DISABLED", defaultValue = "ACTIVE")
            String status,

            @Schema(description = "备注")
            String remark
    ) {
    }

    @Schema(description = "字典项新增/修改请求")
    public record DictDataRequest(
            @Schema(description = "所属字典类型编码", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请选择字典类型")
            String dictType,

            @Schema(description = "展示值", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入字典标签")
            @Size(max = 128, message = "标签长度不能超过 128")
            String dictLabel,

            @Schema(description = "存储值（回传后端的值）", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入字典键值")
            @Size(max = 128, message = "键值长度不能超过 128")
            String dictValue,

            @Schema(description = "显示顺序", defaultValue = "0")
            Integer sort,

            @Schema(description = "标签样式：success/warning/error/info/default")
            String cssClass,

            @Schema(description = "表格回显样式")
            String listClass,

            @Schema(description = "是否默认选中", defaultValue = "false")
            Boolean isDefault,

            @Schema(description = "状态：ACTIVE/DISABLED", defaultValue = "ACTIVE")
            String status,

            @Schema(description = "备注")
            String remark
    ) {
    }
}
