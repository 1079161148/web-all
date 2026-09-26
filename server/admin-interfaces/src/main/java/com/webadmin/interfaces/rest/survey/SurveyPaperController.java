package com.webadmin.interfaces.rest.survey;

import com.webadmin.application.survey.SurveyPaperAppService;
import com.webadmin.application.survey.command.SurveyPaperCommand;
import com.webadmin.application.survey.dto.SurveyPaperDTO;
import com.webadmin.application.survey.port.SurveyPaperPort;
import com.webadmin.application.survey.query.SurveyPaperPageQuery;
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

/**
 * 问卷 / 提纲接口。
 *
 * <p>{@code content} 是富文本 HTML：列表接口不返回，详情接口返回。
 * 前端用 {@code ProEditor} 编辑，图片经 {@code /api/v1/survey/files} 上传后以 URL 插入正文。
 */
@Tag(name = "AI调研-问卷提纲", description = "问卷与访谈提纲（富文本）的维护")
@RestController
@RequestMapping("/api/v1/survey/papers")
@RequiredArgsConstructor
@Validated
public class SurveyPaperController {

    private final SurveyPaperAppService paperAppService;
    private final SurveyPaperPort paperPort;

    @Operation(operationId = "pageSurveyPapers", summary = "分页查询问卷（不含正文）")
    @PreAuthorize("@ps.hasPermission('srvy:paper:query')")
    @GetMapping
    public R<PageResult<PaperResponse>> page(@ParameterObject SurveyPaperPageQuery query) {
        return R.ok(paperPort.page(query).map(PaperResponse::from));
    }

    @Operation(operationId = "getSurveyPaper", summary = "查询问卷详情（含富文本正文）")
    @PreAuthorize("@ps.hasPermission('srvy:paper:query')")
    @GetMapping("/{id}")
    public R<PaperResponse> detail(@PathVariable Long id) {
        return R.ok(PaperResponse.from(paperAppService.detail(id)));
    }

    @Operation(operationId = "createSurveyPaper", summary = "新增问卷")
    @PreAuthorize("@ps.hasPermission('srvy:paper:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody PaperRequest request) {
        return R.ok(paperAppService.create(request.toCommand()));
    }

    @Operation(operationId = "updateSurveyPaper", summary = "修改问卷")
    @PreAuthorize("@ps.hasPermission('srvy:paper:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PaperRequest request) {
        paperAppService.update(id, request.toCommand());
        return R.ok();
    }

    @Operation(operationId = "deleteSurveyPaper", summary = "删除问卷")
    @PreAuthorize("@ps.hasPermission('srvy:paper:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        paperAppService.delete(id);
        return R.ok();
    }

    @Operation(operationId = "bumpSurveyPaperVersion", summary = "问卷升版",
            description = "版本号 +1 并回到草稿态。问卷发布后不就地覆盖，避免历史回收数据与题目对不上")
    @PreAuthorize("@ps.hasPermission('srvy:paper:update')")
    @PostMapping("/{id}/version")
    public R<Void> bumpVersion(@PathVariable Long id) {
        paperAppService.bumpVersion(id);
        return R.ok();
    }

    @Operation(operationId = "batchCreateSurveyPapers", summary = "批量导入问卷（任一行失败整批回滚）")
    @PreAuthorize("@ps.hasPermission('srvy:paper:import')")
    @PostMapping("/batch")
    public R<Integer> batchCreate(@Valid @RequestBody List<PaperRequest> requests) {
        return R.ok(paperAppService.batchCreate(requests.stream().map(PaperRequest::toCommand).toList()));
    }

    // ==================================================================

    @Schema(description = "问卷 / 提纲")
    public record PaperResponse(
            @Schema(description = "ID") Long id,
            @Schema(description = "编码") String paperCode,
            @Schema(description = "标题") String title,
            @Schema(description = "类型：QUESTIONNAIRE/OUTLINE（字典 srvy_paper_type）") String paperType,
            @Schema(description = "版本号") Integer versionNo,
            @Schema(description = "正文（富文本 HTML；列表接口返回 null）") String content,
            @Schema(description = "状态：DRAFT/PUBLISHED/OFFLINE（字典 srvy_paper_status）") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "创建时间") java.time.Instant createTime) {

        public static PaperResponse from(SurveyPaperDTO dto) {
            return dto == null ? null : new PaperResponse(dto.id(), dto.paperCode(), dto.title(),
                    dto.paperType(), dto.versionNo(), dto.content(), dto.status(),
                    dto.remark(), dto.createTime());
        }
    }

    @Schema(description = "问卷新增/修改请求")
    public record PaperRequest(
            @Schema(description = "编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "PAPER-NPS-V4")
            @NotBlank(message = "请输入问卷编码")
            @Size(max = 64, message = "编码长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$",
                    message = "编码只能以字母开头，且仅含字母、数字、下划线与连字符")
            String paperCode,

            @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入标题")
            @Size(max = 200, message = "标题长度不能超过 200")
            String title,

            @Schema(description = "类型（字典 srvy_paper_type）", defaultValue = "QUESTIONNAIRE")
            String paperType,

            @Schema(description = "版本号", defaultValue = "1") Integer versionNo,

            @Schema(description = "正文（富文本 HTML）") String content,

            @Schema(description = "状态（字典 srvy_paper_status）", defaultValue = "DRAFT")
            String status,

            @Schema(description = "备注") @Size(max = 500, message = "备注长度不能超过 500")
            String remark) {

        public SurveyPaperCommand toCommand() {
            return new SurveyPaperCommand(paperCode, title, paperType, versionNo, content, status, remark);
        }
    }
}
