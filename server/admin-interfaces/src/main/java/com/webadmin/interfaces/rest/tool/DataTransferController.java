package com.webadmin.interfaces.rest.tool;

import com.webadmin.application.tool.DataTransferAppService;
import com.webadmin.application.tool.TransferTask;
import com.webadmin.common.api.R;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.interfaces.rest.interceptor.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据导入导出（异步任务）。
 *
 * <h3>下载为什么是"任务 → 链接"而不是直接返回字节</h3>
 * 大数据量导出要几十秒：HTTP 请求挂着等字节，超时/断开就让整场导出白跑。
 * 异步任务 + 轮询进度 + 完成后拿下载链接，才能支撑"取消""重试""多端取件"。
 * 错误报告同理 —— 它也是任务产物的一种。
 *
 * <h3>⚠️ 单权限点</h3>
 * 导入导出共用 {@code tools:transfer:manage}：它们操作的是同一批业务数据
 * 的两个方向，拆成两个权限码的管理成本大于收益。若将来要区分
 * "能导出（泄密风险）"与"能导入（脏数据风险）"，再拆分并做数据迁移。
 */
@Tag(name = "数据导入导出", description = "模板下载 / 异步导入（校验-预览-确认-部分重试）/ 异步导出")
@RestController
@RequestMapping("/api/v1/tools/transfers")
@RequiredArgsConstructor
public class DataTransferController {

    private final DataTransferAppService transferService;

    @Operation(operationId = "downloadTransferTemplate", summary = "下载导入模板",
            description = "xlsx 模板：表头 + 两行示例。示例行展示了每列的合法取值格式")
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return xlsx(transferService.template(), "调研任务导入模板.xlsx");
    }

    @Operation(operationId = "previewImportHeaders", summary = "解析文件表头（列映射预览）",
            description = "只读表头与前 3 行，不建任务 —— 供列映射界面使用；表头与模板不一致时由用户手动映射")
    @RateLimit(limit = 10, windowSeconds = 60)
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @PostMapping(value = "/import-headers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DataTransferAppService.HeaderPreview> previewHeaders(
            @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "请选择要导入的文件");
        }
        return R.ok(transferService.parseHeaders(file.getBytes()));
    }

    @Operation(operationId = "createImportTask", summary = "上传文件并创建导入任务",
            description = "异步执行：解析 → 逐行校验（精确到行列）→ 产出错误清单与合规行预览。"
                    + "校验阶段不写任何业务数据；确认导入需再调 confirm 接口。"
                    + "mapping 为可选列映射（按模板列序给出对应文件列下标，-1=缺席，逗号分隔）；"
                    + "不传则按模板列序直传（表头与模板一致的场景）")
    @RateLimit(limit = 5, windowSeconds = 60)
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, String>> createImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping) throws IOException {
        if (file.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "请选择要导入的文件");
        }
        List<Integer> mappingList = null;
        if (mapping != null && !mapping.isBlank()) {
            try {
                mappingList = java.util.Arrays.stream(mapping.split(","))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .toList();
            } catch (NumberFormatException ex) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "mapping 格式非法（应为逗号分隔的整数）");
            }
        }
        // 租户/部门上下文在 service 内、请求线程上解析并固化进任务（异步线程拿不到）
        String taskId = transferService.createImportTask(
                file.getBytes(), file.getOriginalFilename(), mappingList);
        return R.ok(Map.of("taskId", taskId));
    }

    @Operation(operationId = "confirmImportTask", summary = "确认导入（校验通过后）",
            description = "只导入校验合规的行，分批落库；单行失败按行记录（部分成功语义）")
    @RateLimit(limit = 10, windowSeconds = 60)
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable String id) {
        transferService.confirmImport(id);
        return R.ok();
    }

    @Operation(operationId = "retryImportFailures", summary = "重试失败行",
            description = "部分成功后，只重跑此前失败的行（通常用户已在源数据修复问题）")
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @PostMapping("/{id}/retry")
    public R<Void> retry(@PathVariable String id) {
        transferService.retryFailed(id);
        return R.ok();
    }

    @Operation(operationId = "cancelTransferTask", summary = "取消任务",
            description = "工作线程在批次间隙检查取消标记 —— 已写入的数据不会回滚")
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable String id) {
        transferService.cancel(id);
        return R.ok();
    }

    @Operation(operationId = "getTransferTask", summary = "查询任务进度",
            description = "轮询端点：status/progress/phase + 错误清单（前 500 条）+ 预览行")
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @GetMapping("/{id}")
    public R<TransferTaskView> get(@PathVariable String id) {
        return R.ok(TransferTaskView.from(transferService.get(id)));
    }

    @Operation(operationId = "downloadTransferErrorReport", summary = "下载错误报告",
            description = "xlsx：行号 / 列 / 原值 / 错误原因 —— 用户拿它回源修正后重试")
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @GetMapping("/{id}/error-report")
    public ResponseEntity<byte[]> errorReport(@PathVariable String id) {
        return xlsx(transferService.errorReport(id), "导入错误报告.xlsx");
    }

    @Operation(operationId = "createExportTask", summary = "创建导出任务",
            description = "范围：ALL（全部）/ PAGE / SELECTED（前端传具体 id 列表）。"
                    + "fields 为导出列（空 = 全部字段）。异步生成，完成后经 download 接口取件")
    @RateLimit(limit = 5, windowSeconds = 60)
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @PostMapping("/export")
    public R<Map<String, String>> createExport(@Valid @RequestBody ExportRequest request) {
        DataTransferAppService.ExportScope scope;
        try {
            scope = DataTransferAppService.ExportScope.valueOf(request.scope());
        } catch (IllegalArgumentException ex) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "scope 必须是 ALL / PAGE / SELECTED");
        }
        String taskId = transferService.createExportTask(scope, request.ids(), request.fields());
        return R.ok(Map.of("taskId", taskId));
    }

    @Operation(operationId = "downloadTransferResult", summary = "下载导出文件",
            description = "任务 DONE 后可调用。文件在任务生命周期内可重复下载")
    @PreAuthorize("@ps.hasPermission('tools:transfer:manage')")
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        var task = transferService.get(id);
        return xlsx(transferService.download(id),
                task.resultFileName == null ? "导出结果.xlsx" : task.resultFileName);
    }

    // ==================================================================
    // 契约
    // ==================================================================

    private ResponseEntity<byte[]> xlsx(byte[] content, String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @Schema(description = "导出请求")
    public record ExportRequest(
            @Schema(description = "范围：ALL / PAGE / SELECTED", requiredMode = Schema.RequiredMode.REQUIRED)
            String scope,
            @Schema(description = "PAGE/SELECTED 时的任务 id 列表")
            List<Long> ids,
            @Schema(description = "导出列（空 = 全部字段）")
            List<String> fields) {
    }

    /**
     * 任务视图（轮询响应）。
     *
     * <p>preview 只在 VALIDATED 阶段有意义；errors 上限 500 条 ——
     * 上万行全错时响应体不至于爆炸，完整清单走错误报告文件。
     */
    @Schema(description = "传输任务状态")
    public record TransferTaskView(
            String id,
            String kind,
            String status,
            int progress,
            String phase,
            int totalRows,
            int successRows,
            int failedRows,
            List<ErrorItem> errors,
            List<Map<String, String>> preview,
            boolean hasResultFile,
            String resultFileName) {

        public static TransferTaskView from(TransferTask task) {
            return new TransferTaskView(
                    task.id,
                    task.kind.name(),
                    task.status.name(),
                    task.progress,
                    task.phase,
                    task.totalRows,
                    task.successRows,
                    task.failedRows,
                    task.errors.stream()
                            .map(error -> new ErrorItem(error.row(), error.col(), error.value(), error.message()))
                            .toList(),
                    task.preview,
                    task.resultFile != null,
                    task.resultFileName);
        }
    }

    @Schema(description = "错误行定位")
    public record ErrorItem(
            @Schema(description = "Excel 行号（1 是表头，数据从 2 起）") int row,
            String col,
            String value,
            String message) {
    }
}
