package com.webadmin.application.tool;

import com.webadmin.application.survey.command.SurveyTaskCommand;
import com.webadmin.application.survey.port.SurveyTaskPort;
import com.webadmin.application.tool.port.ExcelPort;
import com.webadmin.application.tool.port.TransferTaskArchivePort;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.UserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据导入导出（异步任务编排）。
 *
 * <h3>导入的两阶段：校验与落库为什么必须分开</h3>
 * "上传即入库"是这类功能最大的坑：1000 行里第 37 行格式错误，
 * 用户面对的是 999 行脏数据 + 一条看不懂的报错。正确语义是：
 * <ol>
 *   <li><b>校验阶段</b>（上传后自动跑）：全量行逐格校验，
 *       产出"精确到行列"的错误清单 + 合规行预览 —— <b>不写任何数据</b></li>
 *   <li><b>确认阶段</b>（用户看过预览后触发）：只导合规行；
 *       违反数据库唯一约束等运行期错误按行捕获，进入"失败行"</li>
 * </ol>
 * 部分成功（990 成功 / 10 失败）不是异常，是正常结局 ——
 * 失败行保留原值，支持"重试失败行"。
 *
 * <h3>异步与上下文</h3>
 * 任务在<b>虚拟线程</b>上执行。两个上下文不会自动跟过去：
 * <ul>
 *   <li>{@code TenantContext}：租户拦截器依赖 —— 提交任务的请求线程捕获，
 *       工作线程开始时恢复、finally 清除</li>
 *   <li>当前用户（部门默认值）：导入行不带部门列，在请求线程解析好
 *       deptId 固化进任务，工作线程不再触碰用户上下文</li>
 * </ul>
 *
 * <h3>任务存储</h3>
 * 权威状态仍在内存 ConcurrentHashMap（轮询接口读内存，毫秒级新鲜度）。
 * 档案表 {@code tool_transfer_task} 只存<b>元数据快照</b>（状态/进度/计数/
 * 错误摘要），解决内存的两块短板：重启丢现场（get() 回落查档案）、
 * 历史审计。档案写入是旁路：创建时与工作线程收尾时各写一次，失败只记日志。
 * 重启后档案里残留的非终态行由 get() 读取时翻译为"重启中断" —— 不引入
 * 启动钩子，因为租户拦截器在无上下文时快速失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTransferAppService {

    private final ExcelPort excelPort;
    private final SurveyTaskPort taskPort;
    private final CurrentUserPort currentUserPort;
    private final UserRepository userRepository;
    private final TransferTaskArchivePort archivePort;

    private final Map<String, TransferTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final int MAX_TASKS = 200;
    private static final int MAX_ROWS = 20_000;
    private static final int IMPORT_BATCH = 20;
    private static final int MAX_ERRORS_KEPT = 500;
    /** 档案保留时长：超过后在下一次创建任务的当日清理中被删除。 */
    private static final int ARCHIVE_RETENTION_DAYS = 7;

    /** 过期档案清理的节流标记：每自然日最多执行一次。 */
    private volatile LocalDate lastArchivePurge = null;

    /** 导入模板列（顺序即模板列顺序）。 */
    private static final List<String> IMPORT_COLUMNS = List.of(
            "任务编码", "任务名称", "任务类型", "优先级", "负责人",
            "开始日期", "结束日期", "进度", "状态", "备注"
    );

    /** 导出全部字段（按导出表头顺序）。 */
    public static final List<String> EXPORT_ALL_FIELDS = List.of(
            "任务编码", "任务名称", "任务类型", "优先级", "负责人",
            "开始日期", "结束日期", "进度", "状态", "备注"
    );

    private static final Set<String> TASK_TYPES = Set.of("SURVEY", "INTERVIEW", "OBSERVE", "DATASET");
    private static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> STATUSES = Set.of("PENDING", "RUNNING", "PAUSED", "DONE");

    // ==================================================================
    // 模板与查询
    // ==================================================================

    /** 导入模板：表头 + 两行示例。 */
    public byte[] template() {
        List<List<String>> samples = List.of(
                List.of("TASK-IMP-001", "示例任务（删除此行后填写）", "SURVEY", "HIGH",
                        "张三", "2026-10-01", "2026-10-31", "0", "PENDING", "由模板导入"),
                List.of("TASK-IMP-002", "另一条示例", "INTERVIEW", "MEDIUM",
                        "李四", "2026-10-05", "2026-11-05", "0", "PENDING", "")
        );
        return excelPort.write("任务导入模板", IMPORT_COLUMNS, samples);
    }

    public TransferTask get(String taskId) {
        TransferTask task = tasks.get(taskId);
        if (task != null) {
            return task;
        }
        // 内存未命中（服务重启后内存必然为空）：回落查档案。
        // 档案里残留的非终态行必然已被重启打断（工作线程已消失）——
        // 翻译成 FAILED 返回，而不是把 "IMPORTING" 的假象给到轮询界面。
        return archivePort.find(taskId)
                .map(this::toArchivedView)
                .orElseThrow(() -> new BizException(CommonErrorCode.RECORD_NOT_FOUND,
                        "任务不存在或已过期"));
    }

    // ==================================================================
    // 导入：阶段一（上传 → 解析 → 校验）
    // ==================================================================

    public String createImportTask(byte[] content, String fileName, List<Integer> mapping) {
        List<Integer> normalized = normalizeMapping(mapping);
        TransferTask task = new TransferTask(newTaskId("IMP"), TransferTask.Kind.IMPORT,
                fileName, currentTenantId(), currentUserDeptId());
        if (tasks.size() >= MAX_TASKS) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "进行中的任务过多，请稍后再试");
        }
        tasks.put(task.id, task);
        maybePurgeArchives();
        archiveUpsert(task);
        executor.submit(() -> runImportValidation(task, content, normalized));
        return task.id;
    }

    /**
     * 列映射预览：只解析表头与前几行，不建任务。
     *
     * <p>让"选文件 → 看表头 → 配映射 → 建任务校验"成为两段交互 ——
     * 映射必须在<b>解析校验之前</b>确定，否则校验错误（按字段报）无从谈起。
     */
    public HeaderPreview parseHeaders(byte[] content) {
        ExcelPort.Sheet sheet;
        try {
            sheet = excelPort.parse(content);
        } catch (IllegalArgumentException ex) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, ex.getMessage());
        }
        if (sheet.headers().isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "文件没有表头行");
        }
        List<List<String>> preview = sheet.rows().subList(
                0, Math.min(3, sheet.rows().size()));
        return new HeaderPreview(sheet.headers(), preview);
    }

    /** 表头预览（headers + 前 3 行样例）。 */
    public record HeaderPreview(List<String> headers, List<List<String>> previewRows) {
    }

    /**
     * 归一化列映射：null = 按模板列序直传（兼容旧路径）。
     *
     * <p>三条硬边界：长度必须等于模板列数（少一个字段就没有归宿）；
     * 索引不得重复（同一列喂给两个字段，几乎必是误操作——
     * 数据会被复制两份且校验器不知道）；
     * 索引必须落在文件列数内（-1 表示"该字段缺席"→ 交给必填校验报错，
     * 复用既有校验器而不是另立规则）。
     */
    private List<Integer> normalizeMapping(List<Integer> mapping) {
        if (mapping == null) {
            return null;
        }
        if (mapping.size() != IMPORT_COLUMNS.size()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "映射数量（" + mapping.size() + "）必须等于模板列数（" + IMPORT_COLUMNS.size() + "）");
        }
        Set<Integer> seen = new HashSet<>();
        for (Integer index : mapping) {
            if (index == null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "映射含空项");
            }
            if (index >= 0 && !seen.add(index)) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "同一个 Excel 列被映射到了多个字段（列 " + (index + 1) + "）");
            }
        }
        return List.copyOf(mapping);
    }

    /** 按 mapping 重排一行为模板列序（-1 = 缺席 → 空值）。 */
    private List<String> remapRow(List<String> values, List<Integer> mapping) {
        List<String> out = new ArrayList<>(mapping.size());
        for (Integer sourceIndex : mapping) {
            out.add(sourceIndex < 0 || sourceIndex >= values.size() ? "" : values.get(sourceIndex));
        }
        return out;
    }

    private void runImportValidation(TransferTask task, byte[] content, List<Integer> mapping) {
        bindContext(task);
        try {
            task.status = TransferTask.Status.PARSING;
            task.phase = "解析 Excel";
            task.progress = 10;
            ExcelPort.Sheet sheet;
            try {
                sheet = excelPort.parse(content);
            } catch (IllegalArgumentException ex) {
                task.status = TransferTask.Status.FAILED;
                task.phase = ex.getMessage();
                return;
            }

            task.status = TransferTask.Status.VALIDATING;
            task.phase = "逐行校验";
            task.totalRows = sheet.rows().size();
            if (task.totalRows > MAX_ROWS) {
                task.status = TransferTask.Status.FAILED;
                task.phase = "行数超过上限（" + MAX_ROWS + " 行），请拆分后导入";
                return;
            }

            List<TransferTask.RowError> errors = new ArrayList<>();
            List<Map<String, String>> validRows = new ArrayList<>();
            Set<String> seenCodes = new HashSet<>();
            Set<String> existingCodes = loadExistingCodes();

            for (int r = 0; r < sheet.rows().size(); r++) {
                if (task.cancelled) {
                    task.status = TransferTask.Status.CANCELLED;
                    task.phase = "已取消";
                    return;
                }
                int excelRow = r + 2; // 第 1 行是表头
                List<String> rawValues = mapping == null
                        ? sheet.rows().get(r)
                        : remapRow(sheet.rows().get(r), mapping);
                Map<String, String> row = toRow(IMPORT_COLUMNS, rawValues);
                int before = errors.size();
                validateRow(excelRow, row, seenCodes, existingCodes, errors);
                // 本行无新增错误 = 合格行
                if (errors.size() == before) {
                    validRows.add(row);
                }
                if (r % 200 == 0) {
                    task.progress = 30 + (int) (30.0 * r / Math.max(1, task.totalRows));
                }
            }

            task.progress = 60;
            task.errors = List.copyOf(errors.size() > MAX_ERRORS_KEPT ? errors.subList(0, MAX_ERRORS_KEPT) : errors);
            task.pendingRows = List.copyOf(validRows);
            task.failedRows = task.totalRows - validRows.size();
            if (validRows.isEmpty()) {
                task.status = TransferTask.Status.FAILED;
                task.phase = "没有可导入的行（全部校验失败或文件为空）";
            } else {
                task.status = TransferTask.Status.VALIDATED;
                task.phase = errors.isEmpty()
                        ? "校验通过，可确认导入"
                        : "校验完成：" + validRows.size() + " 行可导入，" + errors.size() + " 处错误";
            }
            task.preview = List.copyOf(validRows.stream().limit(10).toList());
            log.info("导入校验完成 task={} 总行={} 合格={} 错误={}", task.id, task.totalRows, validRows.size(), errors.size());
        } catch (Exception ex) {
            log.error("导入校验异常 task={}", task.id, ex);
            task.status = TransferTask.Status.FAILED;
            task.phase = "处理异常：" + ex.getMessage();
        } finally {
            archiveUpsert(task);
            TenantContext.clear();
        }
    }

    // ==================================================================
    // 导入：阶段二（确认 → 分批落库 → 部分成功）
    // ==================================================================

    public void confirmImport(String taskId) {
        TransferTask task = require(taskId, TransferTask.Status.VALIDATED);
        List<Map<String, String>> rows = List.copyOf(task.pendingRows);
        task.pendingRows = List.of();
        task.status = TransferTask.Status.IMPORTING;
        task.phase = "批量导入";
        task.totalRows = rows.size();
        // ⚠️ 必须清零校验阶段遗留的失败计数：failedRows 语义是"本轮落库失败行"，
        // 不清零的话"校验 2 错 + 导入 1 成功"会被判成"部分成功"（实测踩过）。
        // errors 列表刻意保留 —— 校验错误仍是错误报告的一部分
        task.failedRows = 0;
        executor.submit(() -> importRows(task, rows));
    }

    /** 部分成功后的重试：只重跑失败行。 */
    public void retryFailed(String taskId) {
        TransferTask task = require(taskId, TransferTask.Status.PARTIAL);
        List<Map<String, String>> rows = List.copyOf(task.failedRowsData);
        if (rows.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "没有可重试的失败行");
        }
        task.failedRowsData = List.of();
        task.failedRows = 0;
        task.status = TransferTask.Status.IMPORTING;
        task.phase = "重试失败行";
        task.totalRows = rows.size();
        task.progress = 0;
        executor.submit(() -> importRows(task, rows));
    }

    /** 批量落库：单行失败按行捕获（唯一约束冲突等），不回滚整批 —— 部分成功语义。 */
    private void importRows(TransferTask task, List<Map<String, String>> rows) {
        bindContext(task);
        try {
            List<Map<String, String>> stillFailed = new ArrayList<>();
            int done = 0;
            for (int i = 0; i < rows.size(); i += IMPORT_BATCH) {
                if (task.cancelled) {
                    task.status = TransferTask.Status.CANCELLED;
                    task.phase = "已取消";
                    return;
                }
                List<Map<String, String>> batch = rows.subList(i, Math.min(i + IMPORT_BATCH, rows.size()));
                for (Map<String, String> row : batch) {
                    try {
                        // 绕过 SurveyTaskAppService.create：其内部读"当前用户"上下文
                        // （异步线程没有）；部门已在请求线程解析并固化
                        taskPort.insert(toCommand(row, task.deptId));
                        task.successRows += 1;
                    } catch (Exception ex) {
                        task.failedRows += 1;
                        stillFailed.add(row);
                        if (task.errors.size() < MAX_ERRORS_KEPT) {
                            task.errors.add(new TransferTask.RowError(
                                    i + done + 2, "任务编码",
                                    row.get("任务编码"),
                                    rootMessage(ex)));
                        }
                    }
                    done += 1;
                }
                task.progress = (int) (100.0 * done / rows.size());
            }
            if (!stillFailed.isEmpty()) {
                task.failedRowsData = List.copyOf(stillFailed);
            }
            if (task.failedRows == 0) {
                task.status = TransferTask.Status.DONE;
                task.phase = "全部导入成功";
            } else if (task.successRows > 0) {
                task.status = TransferTask.Status.PARTIAL;
                task.phase = "部分成功：" + task.successRows + " 行成功，" + task.failedRows + " 行失败（可重试）";
            } else {
                task.status = TransferTask.Status.FAILED;
                task.phase = "全部失败";
            }
            log.info("导入落库完成 task={} 成功={} 失败={}", task.id, task.successRows, task.failedRows);
        } catch (Exception ex) {
            log.error("导入落库异常 task={}", task.id, ex);
            task.status = TransferTask.Status.FAILED;
            task.phase = "处理异常：" + ex.getMessage();
        } finally {
            archiveUpsert(task);
            TenantContext.clear();
        }
    }

    // ==================================================================
    // 导出（异步：分页拉取 → 字段筛选 → 生成 → 进度/取消）
    // ==================================================================

    public String createExportTask(ExportScope scope, List<Long> ids, List<String> fields) {
        TransferTask task = new TransferTask(newTaskId("EXP"), TransferTask.Kind.EXPORT,
                "调研任务导出.xlsx", currentTenantId(), currentUserDeptId());
        tasks.put(task.id, task);
        maybePurgeArchives();
        archiveUpsert(task);
        List<String> effectiveFields = fields == null || fields.isEmpty() ? EXPORT_ALL_FIELDS : fields;
        executor.submit(() -> runExport(task, scope, ids, effectiveFields));
        return task.id;
    }

    // ---- 请求线程内解析上下文（异步工作线程不可用） ----

    private long currentTenantId() {
        return currentUserPort.requireCurrentUser().tenantId();
    }

    private Long currentUserDeptId() {
        var current = currentUserPort.requireCurrentUser();
        return userRepository.findDeptId(UserId.of(current.userId())).orElse(null);
    }

    private void runExport(TransferTask task, ExportScope scope, List<Long> ids, List<String> fields) {
        bindContext(task);
        try {
            task.status = TransferTask.Status.IMPORTING;
            task.phase = "拉取数据";
            task.progress = 5;

            List<Map<String, String>> rows = collectRows(scope, ids, task);
            if (task.cancelled) {
                task.status = TransferTask.Status.CANCELLED;
                task.phase = "已取消";
                return;
            }
            task.totalRows = rows.size();
            task.phase = "生成 Excel";
            task.progress = 70;

            List<List<String>> body = new ArrayList<>();
            for (Map<String, String> row : rows) {
                List<String> values = new ArrayList<>();
                for (String field : fields) {
                    values.add(row.getOrDefault(field, ""));
                }
                body.add(values);
            }
            task.resultFile = excelPort.write("调研任务", fields, body);
            task.resultFileName = "调研任务导出_" + task.id + ".xlsx";
            task.successRows = rows.size();
            task.status = TransferTask.Status.DONE;
            task.phase = "导出完成";
            task.progress = 100;
            log.info("导出完成 task={} 行={} 字段={}", task.id, rows.size(), fields.size());
        } catch (Exception ex) {
            log.error("导出异常 task={}", task.id, ex);
            task.status = TransferTask.Status.FAILED;
            task.phase = "处理异常：" + ex.getMessage();
        } finally {
            archiveUpsert(task);
            TenantContext.clear();
        }
    }

    private List<Map<String, String>> collectRows(ExportScope scope, List<Long> ids, TransferTask task) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (scope != ExportScope.ALL) {
            // 当前页 / 选中：前端把 id 传过来。导出范围由用户视野决定，
            // 服务端不再二次扩大 —— 与列表查询同一条数据权限链路
            if (ids == null || ids.isEmpty()) {
                return rows;
            }
            int done = 0;
            for (Long id : ids) {
                taskPort.findById(id).ifPresent(dto -> rows.add(toExportRow(dto)));
                done += 1;
                if (done % 50 == 0) {
                    task.progress = 5 + (int) (60.0 * done / ids.size());
                }
            }
            return rows;
        }
        // 全部：分页拉取（单页 200，上限 2 万行）
        var query = new com.webadmin.application.survey.query.SurveyTaskPageQuery();
        query.setPage(1);
        query.setSize(200);
        while (true) {
            if (task.cancelled) {
                return rows;
            }
            var page = taskPort.page(query);
            for (var dto : page.records()) {
                rows.add(toExportRow(dto));
            }
            task.progress = Math.min(65, 5 + (int) (60.0 * rows.size() / Math.max(1, page.total())));
            if (page.records().size() < query.getSize() || rows.size() >= 20_000) {
                return rows;
            }
            query.setPage(query.getPage() + 1);
        }
    }

    private Map<String, String> toExportRow(com.webadmin.application.survey.dto.SurveyTaskDTO dto) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("任务编码", dto.taskCode());
        row.put("任务名称", dto.taskName());
        row.put("任务类型", dto.taskType());
        row.put("优先级", dto.priority());
        row.put("负责人", dto.ownerName() == null ? "" : dto.ownerName());
        row.put("开始日期", dto.startDate() == null ? "" : dto.startDate().toString());
        row.put("结束日期", dto.endDate() == null ? "" : dto.endDate().toString());
        row.put("进度", dto.progress() == null ? "" : dto.progress().toString());
        row.put("状态", dto.status());
        row.put("备注", dto.remark() == null ? "" : dto.remark());
        return row;
    }

    // ==================================================================
    // 取消 / 错误报告 / 下载
    // ==================================================================

    public void cancel(String taskId) {
        TransferTask task = get(taskId);
        if (task.status == TransferTask.Status.DONE || task.status == TransferTask.Status.FAILED
                || task.status == TransferTask.Status.CANCELLED) {
            throw new BizException(CommonErrorCode.OPERATION_NOT_ALLOWED, "任务已结束，无法取消");
        }
        task.cancelled = true;
        task.phase = "取消中…";
    }

    /** 错误报告：错误清单（行/列/原值/原因）独立成文件 —— 用户拿着它回源改数据。 */
    public byte[] errorReport(String taskId) {
        TransferTask task = get(taskId);
        List<String> headers = List.of("行号", "列", "原值", "错误原因");
        List<List<String>> body = task.errors.stream()
                .map(error -> List.of(String.valueOf(error.row()), error.col(), error.value(), error.message()))
                .toList();
        return excelPort.write("错误报告", headers, body);
    }

    public byte[] download(String taskId) {
        TransferTask task = get(taskId);
        if (task.resultFile == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "任务尚无结果文件");
        }
        return task.resultFile;
    }

    // ==================================================================
    // 校验规则（错误精确到行列）
    // ==================================================================

    private void validateRow(int excelRow, Map<String, String> row, Set<String> seenCodes,
                             Set<String> existingCodes, List<TransferTask.RowError> errors) {
        String code = row.get("任务编码");
        if (code.isEmpty()) {
            errors.add(new TransferTask.RowError(excelRow, "任务编码", code, "必填"));
        } else if (!code.matches("[A-Za-z0-9_-]{2,64}")) {
            errors.add(new TransferTask.RowError(excelRow, "任务编码", code,
                    "只允许字母、数字、下划线与中划线（2~64 位）"));
        } else if (!seenCodes.add(code)) {
            errors.add(new TransferTask.RowError(excelRow, "任务编码", code, "文件内重复"));
        } else if (existingCodes.contains(code)) {
            errors.add(new TransferTask.RowError(excelRow, "任务编码", code, "系统中已存在相同编码"));
        }

        String name = row.get("任务名称");
        if (name.isEmpty()) {
            errors.add(new TransferTask.RowError(excelRow, "任务名称", name, "必填"));
        } else if (name.length() > 128) {
            errors.add(new TransferTask.RowError(excelRow, "任务名称", name, "长度超过 128"));
        }

        checkEnum(excelRow, row, "任务类型", TASK_TYPES, errors);
        checkEnum(excelRow, row, "优先级", PRIORITIES, errors);
        checkEnum(excelRow, row, "状态", STATUSES, errors);

        LocalDate startDate = parseDate(excelRow, "开始日期", row.get("开始日期"), errors);
        LocalDate endDate = parseDate(excelRow, "结束日期", row.get("结束日期"), errors);
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            errors.add(new TransferTask.RowError(excelRow, "结束日期", row.get("结束日期"),
                    "结束日期早于开始日期"));
        }

        String progress = row.get("进度");
        if (!progress.isEmpty()) {
            try {
                int value = Integer.parseInt(progress);
                if (value < 0 || value > 100) {
                    errors.add(new TransferTask.RowError(excelRow, "进度", progress, "必须在 0~100 之间"));
                }
            } catch (NumberFormatException ex) {
                errors.add(new TransferTask.RowError(excelRow, "进度", progress, "必须是整数"));
            }
        }

        String remark = row.get("备注");
        if (remark.length() > 500) {
            errors.add(new TransferTask.RowError(excelRow, "备注", remark, "长度超过 500"));
        }
    }

    private void checkEnum(int excelRow, Map<String, String> row, String col,
                           Set<String> allowed, List<TransferTask.RowError> errors) {
        String value = row.get(col);
        if (value.isEmpty()) {
            errors.add(new TransferTask.RowError(excelRow, col, value, "必填"));
        } else if (!allowed.contains(value)) {
            errors.add(new TransferTask.RowError(excelRow, col, value,
                    "必须是 " + String.join(" / ", allowed) + " 之一"));
        }
    }

    private LocalDate parseDate(int excelRow, String col, String value,
                                List<TransferTask.RowError> errors) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            errors.add(new TransferTask.RowError(excelRow, col, value, "日期格式应为 yyyy-MM-dd"));
            return null;
        }
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private Map<String, String> toRow(List<String> columns, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), i < values.size() ? values.get(i) : "");
        }
        return row;
    }

    private SurveyTaskCommand toCommand(Map<String, String> row, Long deptId) {
        return new SurveyTaskCommand(
                row.get("任务编码"),
                row.get("任务名称"),
                row.get("任务类型"),
                row.get("优先级"),
                row.get("负责人").isEmpty() ? null : row.get("负责人"),
                deptId,
                row.get("开始日期").isEmpty() ? null : LocalDate.parse(row.get("开始日期")),
                row.get("结束日期").isEmpty() ? null : LocalDate.parse(row.get("结束日期")),
                row.get("进度").isEmpty() ? 0 : Integer.parseInt(row.get("进度")),
                row.get("状态"),
                row.get("备注").isEmpty() ? null : row.get("备注"));
    }

    /** 库内已有编码（唯一性校验）。演示取一大页；生产应走 exists 查询。 */
    private Set<String> loadExistingCodes() {
        var query = new com.webadmin.application.survey.query.SurveyTaskPageQuery();
        query.setPage(1);
        query.setSize(2000);
        Set<String> codes = new HashSet<>();
        for (var dto : taskPort.page(query).records()) {
            codes.add(dto.taskCode());
        }
        return codes;
    }

    // ==================================================================
    // 任务档案（旁路：写失败只记日志，绝不影响任务本身）
    // ==================================================================

    /** 创建任务与工作线程收尾时写一次档案快照（upsert，幂等）。 */
    private void archiveUpsert(TransferTask task) {
        try {
            archivePort.upsert(new TransferTaskArchivePort.ArchivedTask(
                    task.id, task.tenantId, task.deptId, task.kind, task.status,
                    task.phase, task.fileName, task.progress,
                    task.totalRows, task.successRows, task.failedRows,
                    errorSummary(task)));
        } catch (Exception ex) {
            log.warn("任务档案写入失败 task={}", task.id, ex);
        }
    }

    /**
     * 档案行 → 内存任务视图：只回填元数据。
     * 工作数据（校验行、错误明细、结果文件）有意不回填 —— 它们已随重启消失，
     * 视图上对应接口（错误报告/下载）会给出"任务尚无结果文件"之类的明确反馈。
     * 非终态行按"重启中断"翻译为 FAILED。
     */
    private TransferTask toArchivedView(TransferTaskArchivePort.ArchivedTask a) {
        TransferTask view = new TransferTask(a.id(), a.kind(), a.fileName(),
                a.tenantId(), a.deptId());
        boolean unfinished = a.status() != TransferTask.Status.DONE
                && a.status() != TransferTask.Status.PARTIAL
                && a.status() != TransferTask.Status.FAILED
                && a.status() != TransferTask.Status.CANCELLED;
        view.status = unfinished ? TransferTask.Status.FAILED : a.status();
        view.phase = unfinished ? "服务重启，任务中断" : a.phase();
        view.progress = a.progress();
        view.totalRows = a.totalRows();
        view.successRows = a.successRows();
        view.failedRows = a.failedRows();
        return view;
    }

    /** 终态错误摘要（前 3 条拼接；完整明细只存在于内存错误清单）。 */
    private String errorSummary(TransferTask task) {
        if (task.errors.isEmpty()) {
            return "";
        }
        String head = task.errors.stream().limit(3)
                .map(e -> "行" + e.row() + " " + e.col() + "：" + e.message())
                .collect(java.util.stream.Collectors.joining("；"));
        return task.errors.size() > 3
                ? head + "（等 " + task.errors.size() + " 条）"
                : head;
    }

    /**
     * 过期档案清理：每自然日第一次创建任务时顺带执行（内存标记节流）。
     * 刻意不用 @Scheduled：租户拦截器在上下文缺失时快速失败，系统级定时任务
     * 要么循环租户、要么放宽隔离；请求线程顺带执行则两者都不需要，
     * 且清理范围天然落在当前租户内。
     */
    private void maybePurgeArchives() {
        LocalDate today = LocalDate.now();
        if (today.equals(lastArchivePurge)) {
            return;
        }
        lastArchivePurge = today;
        try {
            int removed = archivePort.purgeBefore(
                    LocalDateTime.now().minusDays(ARCHIVE_RETENTION_DAYS));
            if (removed > 0) {
                log.info("已清理 {} 条过期任务档案（超过 {} 天）",
                        removed, ARCHIVE_RETENTION_DAYS);
            }
        } catch (Exception ex) {
            // 清理是旁路：失败只影响存储增长，下个自然日还会再试
            log.warn("过期任务档案清理失败", ex);
        }
    }

    private TransferTask require(String taskId, TransferTask.Status expected) {
        TransferTask task = get(taskId);
        if (task.status != expected) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "任务当前状态为 " + task.status + "，需要 " + expected);
        }
        return task;
    }

    private void bindContext(TransferTask task) {
        TenantContext.set(task.tenantId);
    }

    private String newTaskId(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** 导出范围。 */
    public enum ExportScope { ALL, PAGE, SELECTED }
}
