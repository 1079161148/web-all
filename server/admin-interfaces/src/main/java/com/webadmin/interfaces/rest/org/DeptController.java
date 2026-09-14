package com.webadmin.interfaces.rest.org;

import com.webadmin.application.iam.DeptAppService;
import com.webadmin.application.iam.dto.DeptDTO;
import com.webadmin.application.iam.port.DeptQueryPort;
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
 * 部门管理接口。
 *
 * <h3>返回扁平结构，并带上 {@code ancestors}</h3>
 * 前端建树需要 parentId，而"展开到指定节点"与"显示层级"还需要 ancestors。
 * 一并返回可让前端省掉递归查询 —— 这是物化路径方案对前端的额外收益。
 *
 * <h3>移动部门是本模块最危险的操作</h3>
 * 它需要<b>级联改写整棵子树的 ancestors</b>，且必须防环。
 * 相关逻辑在 {@code DeptAppService.updateDept}，此处只做参数转交。
 */
@Tag(name = "部门管理", description = "部门树维护。移动部门会级联更新子树路径")
@RestController
@RequestMapping("/api/v1/iam/depts")
@RequiredArgsConstructor
@Validated
public class DeptController {

    private final DeptAppService deptAppService;
    private final DeptQueryPort deptQueryPort;

    @Operation(operationId = "listDepts", summary = "查询全部部门（扁平结构）",
            description = "含 ancestors 物化路径，前端可直接用于建树与展开定位")
    @PreAuthorize("@ps.hasPermission('org:dept:query')")
    @GetMapping
    public R<List<DeptDTO>> list() {
        return R.ok(deptQueryPort.listAll());
    }

    @Operation(operationId = "getDept", summary = "查询部门详情")
    @PreAuthorize("@ps.hasPermission('org:dept:query')")
    @GetMapping("/{id}")
    public R<DeptDTO> detail(@PathVariable Long id) {
        return R.ok(deptQueryPort.findById(id).orElseThrow(
                () -> new BizException(IamErrorCode.DEPT_NOT_FOUND, "部门不存在")));
    }

    @Operation(operationId = "createDept", summary = "新增部门",
            description = "上级部门为空时挂到根节点，其 ancestors 自动计算")
    @PreAuthorize("@ps.hasPermission('org:dept:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody DeptRequest request) {
        return R.ok(deptAppService.createDept(
                request.parentId(), request.deptName(), request.sort(), request.leaderUserId(),
                request.phone(), request.email(), request.status(), request.remark()));
    }

    @Operation(operationId = "updateDept", summary = "修改部门",
            description = "变更上级部门时会级联更新整棵子树的路径；不允许移动到自己的子部门下（会形成环）")
    @PreAuthorize("@ps.hasPermission('org:dept:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody DeptRequest request) {
        deptAppService.updateDept(id, request.parentId(), request.deptName(), request.sort(),
                request.leaderUserId(), request.phone(), request.email(),
                request.status(), request.remark());
        return R.ok();
    }

    @Operation(operationId = "deleteDept", summary = "删除部门",
            description = "存在子部门或仍有员工时拒绝删除，并在提示中给出具体人数")
    @PreAuthorize("@ps.hasPermission('org:dept:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        deptAppService.deleteDept(id);
        return R.ok();
    }

    // ==================================================================

    @Schema(description = "部门新增/修改请求")
    public record DeptRequest(
            @Schema(description = "上级部门 ID，0 或留空表示根节点")
            Long parentId,

            @Schema(description = "部门名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入部门名称")
            @Size(max = 64, message = "部门名称长度不能超过 64")
            String deptName,

            @Schema(description = "显示顺序", defaultValue = "0")
            Integer sort,

            @Schema(description = "负责人用户 ID")
            Long leaderUserId,

            @Schema(description = "联系电话")
            String phone,

            @Schema(description = "邮箱")
            String email,

            @Schema(description = "状态：ACTIVE/DISABLED", defaultValue = "ACTIVE")
            String status,

            @Schema(description = "备注")
            String remark
    ) {
    }
}
