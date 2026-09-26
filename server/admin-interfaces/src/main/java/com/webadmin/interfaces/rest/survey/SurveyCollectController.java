package com.webadmin.interfaces.rest.survey;

import com.webadmin.application.survey.SurveyCollectAppService;
import com.webadmin.application.survey.command.SurveyCollectCommand;
import com.webadmin.application.survey.dto.SurveyCollectDTO;
import com.webadmin.application.survey.port.SurveyCollectPort;
import com.webadmin.application.survey.query.SurveyCollectPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** 数据采集接口（附件的上传见 {@link SurveyFileController}）。 */
@Tag(name = "AI调研-数据采集", description = "调研数据采集记录（含数据文件）")
@RestController
@RequestMapping("/api/v1/survey/collects")
@RequiredArgsConstructor
@Validated
public class SurveyCollectController {

    private final SurveyCollectAppService collectAppService;
    private final SurveyCollectPort collectPort;

    @Operation(operationId = "pageSurveyCollects", summary = "分页查询采集记录")
    @PreAuthorize("@ps.hasPermission('srvy:collect:query')")
    @GetMapping
    public R<PageResult<CollectResponse>> page(@ParameterObject SurveyCollectPageQuery query) {
        return R.ok(collectPort.page(query).map(CollectResponse::from));
    }

    @Operation(operationId = "getSurveyCollect", summary = "查询采集记录详情")
    @PreAuthorize("@ps.hasPermission('srvy:collect:query')")
    @GetMapping("/{id}")
    public R<CollectResponse> detail(@PathVariable Long id) {
        return R.ok(CollectResponse.from(collectAppService.detail(id)));
    }

    @Operation(operationId = "createSurveyCollect", summary = "新增采集记录",
            description = "taskName 由后端按 taskId 取任务名快照，前端不必传")
    @PreAuthorize("@ps.hasPermission('srvy:collect:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody CollectRequest request) {
        return R.ok(collectAppService.create(request.toCommand()));
    }

    @Operation(operationId = "updateSurveyCollect", summary = "修改采集记录")
    @PreAuthorize("@ps.hasPermission('srvy:collect:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody CollectRequest request) {
        collectAppService.update(id, request.toCommand());
        return R.ok();
    }

    @Operation(operationId = "deleteSurveyCollect", summary = "删除采集记录")
    @PreAuthorize("@ps.hasPermission('srvy:collect:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        collectAppService.delete(id);
        return R.ok();
    }

    @Operation(operationId = "batchCreateSurveyCollects", summary = "批量导入采集记录（Excel 导入用）")
    @PreAuthorize("@ps.hasPermission('srvy:collect:import')")
    @PostMapping("/batch")
    public R<Integer> batchCreate(@Valid @RequestBody List<CollectRequest> requests) {
        return R.ok(collectAppService.batchCreate(requests.stream().map(CollectRequest::toCommand).toList()));
    }

    // ==================================================================

    @Schema(description = "采集记录")
    public record CollectResponse(
            @Schema(description = "ID") Long id,
            @Schema(description = "所属任务 ID") Long taskId,
            @Schema(description = "所属任务名称（写入时快照）") String taskName,
            @Schema(description = "渠道：ONLINE/OFFLINE/PHONE/EMAIL（字典 srvy_channel）") String channel,
            @Schema(description = "采集人") String collector,
            @Schema(description = "采集日期") LocalDate collectDate,
            @Schema(description = "计划样本量") Integer sampleCount,
            @Schema(description = "有效样本量") Integer validCount,
            @Schema(description = "数据质量评分 0~100") Integer qualityScore,
            @Schema(description = "数据文件 ID") Long fileId,
            @Schema(description = "数据文件名") String fileName,
            @Schema(description = "状态：COLLECTING/FINISHED/ABORTED（字典 srvy_collect_status）") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "创建时间") java.time.Instant createTime) {

        public static CollectResponse from(SurveyCollectDTO dto) {
            return dto == null ? null : new CollectResponse(dto.id(), dto.taskId(), dto.taskName(),
                    dto.channel(), dto.collector(), dto.collectDate(), dto.sampleCount(),
                    dto.validCount(), dto.qualityScore(), dto.fileId(), dto.fileName(),
                    dto.status(), dto.remark(), dto.createTime());
        }
    }

    @Schema(description = "采集记录新增/修改请求")
    public record CollectRequest(
            @Schema(description = "所属任务 ID", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "请选择所属调研任务")
            Long taskId,

            @Schema(description = "渠道（字典 srvy_channel）", defaultValue = "ONLINE")
            String channel,

            @Schema(description = "采集人") @Size(max = 64, message = "采集人长度不能超过 64")
            String collector,

            @Schema(description = "采集日期", example = "2026-09-18")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate collectDate,

            @Schema(description = "计划样本量", defaultValue = "0")
            @Min(value = 0, message = "样本量不能为负") Integer sampleCount,

            @Schema(description = "有效样本量", defaultValue = "0")
            @Min(value = 0, message = "样本量不能为负") Integer validCount,

            @Schema(description = "数据质量评分 0~100", defaultValue = "0")
            @Min(value = 0, message = "评分不能小于 0")
            @Max(value = 100, message = "评分不能大于 100")
            Integer qualityScore,

            @Schema(description = "数据文件 ID（先调上传接口拿到 id）") Long fileId,

            @Schema(description = "数据文件名") @Size(max = 255, message = "文件名过长") String fileName,

            @Schema(description = "状态（字典 srvy_collect_status）", defaultValue = "COLLECTING")
            String status,

            @Schema(description = "备注") @Size(max = 500, message = "备注长度不能超过 500")
            String remark) {

        public SurveyCollectCommand toCommand() {
            // taskName 传 null：它不是入参，由应用层按 taskId 取任务名后填成快照
            return new SurveyCollectCommand(taskId, null, channel, collector, collectDate,
                    sampleCount, validCount, qualityScore, fileId, fileName, status, remark);
        }
    }
}
