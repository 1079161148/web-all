package com.webadmin.interfaces.rest.survey;

import com.webadmin.application.survey.SurveyTemplateAppService;
import com.webadmin.application.survey.command.SurveyTemplateCommand;
import com.webadmin.application.survey.dto.SurveyTemplateDTO;
import com.webadmin.application.survey.port.SurveyTemplatePort;
import com.webadmin.application.survey.query.SurveyTemplatePageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
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

/** 调研模板库接口。 */
@Tag(name = "AI调研-模板库", description = "可复用的问卷/提纲模板")
@RestController
@RequestMapping("/api/v1/survey/templates")
@RequiredArgsConstructor
@Validated
public class SurveyTemplateController {

    private final SurveyTemplateAppService templateAppService;
    private final SurveyTemplatePort templatePort;

    @Operation(operationId = "pageSurveyTemplates", summary = "分页查询模板（不含正文）")
    @PreAuthorize("@ps.hasPermission('srvy:template:query')")
    @GetMapping
    public R<PageResult<TemplateResponse>> page(@ParameterObject SurveyTemplatePageQuery query) {
        return R.ok(templatePort.page(query).map(TemplateResponse::from));
    }

    @Operation(operationId = "getSurveyTemplate", summary = "查询模板详情（含富文本正文）")
    @PreAuthorize("@ps.hasPermission('srvy:template:query')")
    @GetMapping("/{id}")
    public R<TemplateResponse> detail(@PathVariable Long id) {
        return R.ok(TemplateResponse.from(templateAppService.detail(id)));
    }

    @Operation(operationId = "createSurveyTemplate", summary = "新增模板")
    @PreAuthorize("@ps.hasPermission('srvy:template:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody TemplateRequest request) {
        return R.ok(templateAppService.create(request.toCommand()));
    }

    @Operation(operationId = "updateSurveyTemplate", summary = "修改模板")
    @PreAuthorize("@ps.hasPermission('srvy:template:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody TemplateRequest request) {
        templateAppService.update(id, request.toCommand());
        return R.ok();
    }

    @Operation(operationId = "deleteSurveyTemplate", summary = "删除模板")
    @PreAuthorize("@ps.hasPermission('srvy:template:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        templateAppService.delete(id);
        return R.ok();
    }

    @Operation(operationId = "applySurveyTemplate", summary = "套用模板",
            description = "返回模板全文并把引用次数 +1。前端据此把正文复制进问卷编辑器")
    @PreAuthorize("@ps.hasPermission('srvy:template:query')")
    @PostMapping("/{id}/apply")
    public R<TemplateResponse> apply(@PathVariable Long id) {
        return R.ok(TemplateResponse.from(templateAppService.apply(id)));
    }

    @Operation(operationId = "batchCreateSurveyTemplates", summary = "批量导入模板（Excel 导入用）")
    @PreAuthorize("@ps.hasPermission('srvy:template:import')")
    @PostMapping("/batch")
    public R<Integer> batchCreate(@Valid @RequestBody List<TemplateRequest> requests) {
        return R.ok(templateAppService.batchCreate(
                requests.stream().map(TemplateRequest::toCommand).toList()));
    }

    // ==================================================================

    @Schema(description = "调研模板")
    public record TemplateResponse(
            @Schema(description = "ID") Long id,
            @Schema(description = "模板编码") String templateCode,
            @Schema(description = "模板名称") String templateName,
            @Schema(description = "分类：NPS/USABILITY/INTERVIEW/OTHER（字典 srvy_template_category）") String category,
            @Schema(description = "正文（富文本 HTML；列表接口返回 null）") String content,
            @Schema(description = "引用次数") Integer usageCount,
            @Schema(description = "状态（字典 sys_status）") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "创建时间") java.time.Instant createTime) {

        public static TemplateResponse from(SurveyTemplateDTO dto) {
            return dto == null ? null : new TemplateResponse(dto.id(), dto.templateCode(),
                    dto.templateName(), dto.category(), dto.content(), dto.usageCount(),
                    dto.status(), dto.remark(), dto.createTime());
        }
    }

    @Schema(description = "模板新增/修改请求")
    public record TemplateRequest(
            @Schema(description = "模板编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TPL-NPS-02")
            @NotBlank(message = "请输入模板编码")
            @Size(max = 64, message = "编码长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$",
                    message = "编码只能以字母开头，且仅含字母、数字、下划线与连字符")
            String templateCode,

            @Schema(description = "模板名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入模板名称")
            @Size(max = 128, message = "名称长度不能超过 128")
            String templateName,

            @Schema(description = "分类（字典 srvy_template_category）", defaultValue = "NPS")
            String category,

            @Schema(description = "正文（富文本 HTML）") String content,

            @Schema(description = "引用次数（一般由套用接口维护，新增时可省略）") Integer usageCount,

            @Schema(description = "状态（字典 sys_status）", defaultValue = "ACTIVE")
            String status,

            @Schema(description = "备注") @Size(max = 500, message = "备注长度不能超过 500")
            String remark) {

        public SurveyTemplateCommand toCommand() {
            return new SurveyTemplateCommand(templateCode, templateName, category, content,
                    usageCount, status, remark);
        }
    }
}
