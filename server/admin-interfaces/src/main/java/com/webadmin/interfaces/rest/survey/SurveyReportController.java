package com.webadmin.interfaces.rest.survey;

import com.webadmin.application.survey.SurveyReportAppService;
import com.webadmin.application.survey.command.SurveyReportCommand;
import com.webadmin.application.survey.dto.SurveyReportDTO;
import com.webadmin.application.survey.port.SurveyReportPort;
import com.webadmin.application.survey.query.SurveyReportPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
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

/** 调研分析报告接口。 */
@Tag(name = "AI调研-分析报告", description = "调研分析报告（富文本 + 附件）")
@RestController
@RequestMapping("/api/v1/survey/reports")
@RequiredArgsConstructor
@Validated
public class SurveyReportController {

    private final SurveyReportAppService reportAppService;
    private final SurveyReportPort reportPort;

    @Operation(operationId = "pageSurveyReports", summary = "分页查询报告（不含正文）")
    @PreAuthorize("@ps.hasPermission('srvy:report:query')")
    @GetMapping
    public R<PageResult<ReportResponse>> page(@ParameterObject SurveyReportPageQuery query) {
        return R.ok(reportPort.page(query).map(ReportResponse::from));
    }

    @Operation(operationId = "getSurveyReport", summary = "查询报告详情（含富文本正文）")
    @PreAuthorize("@ps.hasPermission('srvy:report:query')")
    @GetMapping("/{id}")
    public R<ReportResponse> detail(@PathVariable Long id) {
        return R.ok(ReportResponse.from(reportAppService.detail(id)));
    }

    @Operation(operationId = "createSurveyReport", summary = "新增报告")
    @PreAuthorize("@ps.hasPermission('srvy:report:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody ReportRequest request) {
        return R.ok(reportAppService.create(request.toCommand()));
    }

    @Operation(operationId = "updateSurveyReport", summary = "修改报告")
    @PreAuthorize("@ps.hasPermission('srvy:report:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ReportRequest request) {
        reportAppService.update(id, request.toCommand());
        return R.ok();
    }

    @Operation(operationId = "deleteSurveyReport", summary = "删除报告")
    @PreAuthorize("@ps.hasPermission('srvy:report:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        reportAppService.delete(id);
        return R.ok();
    }

    @Operation(operationId = "batchCreateSurveyReports", summary = "批量导入报告（Excel 导入用）")
    @PreAuthorize("@ps.hasPermission('srvy:report:import')")
    @PostMapping("/batch")
    public R<Integer> batchCreate(@Valid @RequestBody List<ReportRequest> requests) {
        return R.ok(reportAppService.batchCreate(requests.stream().map(ReportRequest::toCommand).toList()));
    }

    // ==================================================================

    @Schema(description = "调研报告")
    public record ReportResponse(
            @Schema(description = "ID") Long id,
            @Schema(description = "所属任务 ID") Long taskId,
            @Schema(description = "所属任务名称（写入时快照）") String taskName,
            @Schema(description = "报告标题") String reportTitle,
            @Schema(description = "报告类型：SUMMARY/CROSS/CUSTOM（字典 srvy_report_type）") String reportType,
            @Schema(description = "撰写人") String author,
            @Schema(description = "发布日期") LocalDate publishDate,
            @Schema(description = "摘要") String summary,
            @Schema(description = "正文（富文本 HTML；列表接口返回 null）") String content,
            @Schema(description = "附件 ID") Long fileId,
            @Schema(description = "附件名") String fileName,
            @Schema(description = "状态：DRAFT/REVIEWING/PUBLISHED（字典 srvy_report_status）") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "创建时间") java.time.Instant createTime) {

        public static ReportResponse from(SurveyReportDTO dto) {
            return dto == null ? null : new ReportResponse(dto.id(), dto.taskId(), dto.taskName(),
                    dto.reportTitle(), dto.reportType(), dto.author(), dto.publishDate(),
                    dto.summary(), dto.content(), dto.fileId(), dto.fileName(), dto.status(),
                    dto.remark(), dto.createTime());
        }
    }

    @Schema(description = "报告新增/修改请求")
    public record ReportRequest(
            @Schema(description = "所属任务 ID", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "请选择所属调研任务")
            Long taskId,

            @Schema(description = "报告标题", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入报告标题")
            @Size(max = 200, message = "标题长度不能超过 200")
            String reportTitle,

            @Schema(description = "报告类型（字典 srvy_report_type）", defaultValue = "SUMMARY")
            String reportType,

            @Schema(description = "撰写人") @Size(max = 64, message = "撰写人长度不能超过 64")
            String author,

            @Schema(description = "发布日期", example = "2026-09-18")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate publishDate,

            @Schema(description = "摘要") @Size(max = 500, message = "摘要长度不能超过 500")
            String summary,

            @Schema(description = "正文（富文本 HTML）") String content,

            @Schema(description = "附件 ID（先调上传接口拿到 id）") Long fileId,

            @Schema(description = "附件名") @Size(max = 255, message = "文件名过长") String fileName,

            @Schema(description = "状态（字典 srvy_report_status）", defaultValue = "DRAFT")
            String status,

            @Schema(description = "备注") @Size(max = 500, message = "备注长度不能超过 500")
            String remark) {

        public SurveyReportCommand toCommand() {
            // taskName 传 null：它不是入参，由应用层按 taskId 取任务名后填成快照
            return new SurveyReportCommand(taskId, null, reportTitle, reportType, author,
                    publishDate, summary, content, fileId, fileName, status, remark);
        }
    }
}
