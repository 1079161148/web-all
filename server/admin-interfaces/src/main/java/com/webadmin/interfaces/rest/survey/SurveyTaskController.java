package com.webadmin.interfaces.rest.survey;

import com.webadmin.application.survey.SurveyTaskAppService;
import com.webadmin.application.survey.command.SurveyTaskCommand;
import com.webadmin.application.survey.dto.SurveyTaskDTO;
import com.webadmin.application.survey.port.SurveyTaskPort;
import com.webadmin.application.survey.query.SurveyTaskPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.survey.SurveyErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

/**
 * 调研任务接口。
 *
 * <p>AI 调研上下文的 5 个模块接口风格统一：分页 / 详情 / 新增 / 修改 / 删除 / 批量导入，
 * 权限码统一为 {@code srvy:{模块}:{动作}}。
 */
@Tag(name = "AI调研-任务", description = "调研任务的增删改查与批量导入")
@RestController
@RequestMapping("/api/v1/survey/tasks")
@RequiredArgsConstructor
@Validated
public class SurveyTaskController {

    private final SurveyTaskAppService taskAppService;
    private final SurveyTaskPort taskPort;

    @Operation(operationId = "pageSurveyTasks", summary = "分页查询调研任务")
    @PreAuthorize("@ps.hasPermission('srvy:task:query')")
    @GetMapping
    public R<PageResult<TaskResponse>> page(@ParameterObject SurveyTaskPageQuery query) {
        return R.ok(taskPort.page(query).map(TaskResponse::from));
    }

    @Operation(operationId = "listSurveyTaskOptions", summary = "全部任务（下拉选项）",
            description = "供数据采集、报告表单选择所属任务；按创建时间倒序，最多 200 条")
    @PreAuthorize("@ps.hasPermission('srvy:task:query')")
    @GetMapping("/options")
    public R<List<TaskOption>> options() {
        return R.ok(taskPort.listAll().stream().map(TaskOption::from).toList());
    }

    @Operation(operationId = "getSurveyTask", summary = "查询任务详情")
    @PreAuthorize("@ps.hasPermission('srvy:task:query')")
    @GetMapping("/{id}")
    public R<TaskResponse> detail(@PathVariable Long id) {
        return R.ok(taskPort.findById(id).map(TaskResponse::from)
                .orElseThrow(() -> new BizException(SurveyErrorCode.TASK_NOT_FOUND, "调研任务不存在")));
    }

    @Operation(operationId = "createSurveyTask", summary = "新增调研任务")
    @PreAuthorize("@ps.hasPermission('srvy:task:create')")
    @PostMapping
    public R<Long> create(@Valid @RequestBody TaskRequest request) {
        return R.ok(taskAppService.create(request.toCommand()));
    }

    @Operation(operationId = "updateSurveyTask", summary = "修改调研任务")
    @PreAuthorize("@ps.hasPermission('srvy:task:update')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody TaskRequest request) {
        taskAppService.update(id, request.toCommand());
        return R.ok();
    }

    @Operation(operationId = "deleteSurveyTask", summary = "删除调研任务")
    @PreAuthorize("@ps.hasPermission('srvy:task:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        taskAppService.delete(id);
        return R.ok();
    }

    @Operation(operationId = "batchCreateSurveyTasks", summary = "批量导入调研任务",
            description = "Excel 导入用。任一行失败则整批回滚，错误消息带行号，便于改完文件重传")
    @PreAuthorize("@ps.hasPermission('srvy:task:import')")
    @PostMapping("/batch")
    public R<Integer> batchCreate(@Valid @RequestBody List<TaskRequest> requests) {
        return R.ok(taskAppService.batchCreate(requests.stream().map(TaskRequest::toCommand).toList()));
    }

    // ==================================================================

    @Schema(description = "调研任务")
    public record TaskResponse(
            @Schema(description = "任务 ID") Long id,
            @Schema(description = "任务编码") String taskCode,
            @Schema(description = "任务名称") String taskName,
            @Schema(description = "任务类型：SURVEY/INTERVIEW/OBSERVE/DATASET（字典 srvy_task_type）") String taskType,
            @Schema(description = "优先级：HIGH/MEDIUM/LOW（字典 srvy_priority）") String priority,
            @Schema(description = "负责人") String ownerName,
            @Schema(description = "负责部门 ID") Long deptId,
            @Schema(description = "计划开始日期") LocalDate startDate,
            @Schema(description = "计划结束日期") LocalDate endDate,
            @Schema(description = "进度百分比 0~100") Integer progress,
            @Schema(description = "任务状态：PENDING/RUNNING/PAUSED/DONE（字典 srvy_task_status）") String status,
            @Schema(description = "备注") String remark,
            @Schema(description = "创建时间") java.time.Instant createTime) {

        public static TaskResponse from(SurveyTaskDTO dto) {
            return dto == null ? null : new TaskResponse(dto.id(), dto.taskCode(), dto.taskName(),
                    dto.taskType(), dto.priority(), dto.ownerName(), dto.deptId(),
                    dto.startDate(), dto.endDate(), dto.progress(), dto.status(),
                    dto.remark(), dto.createTime());
        }
    }

    @Schema(description = "任务下拉选项")
    public record TaskOption(
            @Schema(description = "任务 ID") Long id,
            @Schema(description = "任务编码") String taskCode,
            @Schema(description = "任务名称") String taskName) {

        public static TaskOption from(SurveyTaskDTO dto) {
            return new TaskOption(dto.id(), dto.taskCode(), dto.taskName());
        }
    }

    @Schema(description = "调研任务新增/修改请求")
    public record TaskRequest(
            @Schema(description = "任务编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TASK-NPS-2026Q4")
            @NotBlank(message = "请输入任务编码")
            @Size(max = 64, message = "任务编码长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$",
                    message = "任务编码只能以字母开头，且仅含字母、数字、下划线与连字符")
            String taskCode,

            @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入任务名称")
            @Size(max = 128, message = "任务名称长度不能超过 128")
            String taskName,

            @Schema(description = "任务类型（字典 srvy_task_type）", defaultValue = "SURVEY")
            String taskType,

            @Schema(description = "优先级（字典 srvy_priority）", defaultValue = "MEDIUM")
            String priority,

            @Schema(description = "负责人") @Size(max = 64, message = "负责人长度不能超过 64")
            String ownerName,

            @Schema(description = "负责部门 ID") Long deptId,

            @Schema(description = "计划开始日期", example = "2026-10-01")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @Schema(description = "计划结束日期", example = "2026-12-31")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,

            @Schema(description = "进度百分比 0~100", defaultValue = "0")
            @Min(value = 0, message = "进度不能小于 0")
            @Max(value = 100, message = "进度不能大于 100")
            Integer progress,

            @Schema(description = "任务状态（字典 srvy_task_status）", defaultValue = "PENDING")
            String status,

            @Schema(description = "备注") @Size(max = 500, message = "备注长度不能超过 500")
            String remark) {

        public SurveyTaskCommand toCommand() {
            return new SurveyTaskCommand(taskCode, taskName, taskType, priority, ownerName,
                    deptId, startDate, endDate, progress, status, remark);
        }
    }
}
