package com.webadmin.interfaces.rest.org;

import com.webadmin.application.org.PostAppService;
import com.webadmin.application.org.dto.PostDTO;
import com.webadmin.application.org.port.PostPort;
import com.webadmin.application.org.query.PostPageQuery;
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

/** 岗位管理接口。 */
@Tag(name = "岗位管理", description = "岗位的增删改查")
@RestController
@RequestMapping("/api/v1/iam/posts")
@RequiredArgsConstructor
@Validated
public class PostController {

    private final PostAppService postAppService;
    private final PostPort postPort;

    @Operation(operationId = "pagePosts", summary = "分页查询岗位",
            description = "返回关联员工数，便于删除前判断占用")
    @PreAuthorize("@ps.hasPermission('org:post:query')")
    @GetMapping
    public R<PageResult<PostResponse>> page(@ParameterObject PostPageQuery query) {
        return R.ok(postPort.page(query).map(PostResponse::from));
    }

    @Operation(operationId = "listUsablePosts", summary = "查询全部启用岗位",
            description = "供用户表单的岗位多选使用。不分页：岗位数量在几十个量级")
    @PreAuthorize("@ps.hasPermission('org:post:query')")
    @GetMapping("/usable")
    public R<List<PostResponse>> usable() {
        return R.ok(postPort.findUsable().stream().map(PostResponse::from).toList());
    }

    @Operation(operationId = "getPost", summary = "查询岗位详情")
    @PreAuthorize("@ps.hasPermission('org:post:query')")
    @GetMapping("/{id}")
    public R<PostResponse> detail(@PathVariable Long id) {
        return R.ok(postPort.findById(id).map(PostResponse::from)
                .orElseThrow(() -> new BizException(IamErrorCode.POST_NOT_FOUND, "岗位不存在")));
    }

    @Operation(operationId = "createPost", summary = "新增岗位")
    @PreAuthorize("@ps.hasPermission('org:post:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody PostRequest request) {
        return R.ok(postAppService.create(request.postCode(), request.postName(),
                request.sort(), request.status(), request.remark()));
    }

    @Operation(operationId = "updatePost", summary = "修改岗位")
    @PreAuthorize("@ps.hasPermission('org:post:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PostRequest request) {
        postAppService.update(id, request.postCode(), request.postName(),
                request.sort(), request.status(), request.remark());
        return R.ok();
    }

    @Operation(operationId = "deletePost", summary = "删除岗位",
            description = "仍分配给员工时拒绝删除并提示具体人数")
    @PreAuthorize("@ps.hasPermission('org:post:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        postAppService.delete(id);
        return R.ok();
    }

    // ==================================================================

    @Schema(description = "岗位信息")
    public record PostResponse(
            @Schema(description = "岗位 ID") Long id,
            @Schema(description = "岗位编码") String postCode,
            @Schema(description = "岗位名称") String postName,
            @Schema(description = "显示顺序") Integer sort,
            @Schema(description = "状态：ACTIVE/DISABLED") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "关联员工数") Integer userCount
    ) {
        public static PostResponse from(PostDTO dto) {
            return dto == null ? null : new PostResponse(dto.id(), dto.postCode(),
                    dto.postName(), dto.sort(), dto.status(), dto.remark(), dto.userCount());
        }
    }

    @Schema(description = "岗位新增/修改请求")
    public record PostRequest(
            @Schema(description = "岗位编码，大写字母/数字/下划线", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "DEV")
            @NotBlank(message = "请输入岗位编码")
            @Size(max = 64, message = "编码长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
                    message = "编码只能以字母开头，且仅含字母、数字与下划线")
            String postCode,

            @Schema(description = "岗位名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入岗位名称")
            @Size(max = 64, message = "名称长度不能超过 64")
            String postName,

            @Schema(description = "显示顺序", defaultValue = "0")
            Integer sort,

            @Schema(description = "状态：ACTIVE/DISABLED", defaultValue = "ACTIVE")
            String status,

            @Schema(description = "备注")
            String remark
    ) {
    }
}
