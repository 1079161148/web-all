package com.webadmin.application.tool;

import java.util.List;
import java.util.Map;

/**
 * 数据传输任务（导入/导出）的内存状态。
 *
 * <h3>为什么内存而非数据库表</h3>
 * 任务是**短生命周期**的运行时状态（分钟级），且断点恢复语义由"重新上传"
 * 承担。生产化路径很明确：换成 {@code transfer_task} 表 + 定时清理，
 * 接口形状不变 —— 这正是把任务收敛为一个类型（而不是散在多个接口参数里）
 * 的目的：换存储只动这一个类。
 *
 * <h3>并发约定</h3>
 * 一个任务同一时刻只有工作线程在写、轮询请求在读：可变进度字段用
 * {@code volatile}，错误列表与预览数据在最终赋值前不可见（构建完成后
 * 一次性发布），取消用 {@code volatile boolean}。
 */
public class TransferTask {

    public enum Kind { IMPORT, EXPORT }

    /**
     * 导入任务的生命周期：QUEUED → PARSING → VALIDATED →(确认)→ IMPORTING
     * → DONE / PARTIAL；任何阶段可 → CANCELLED / FAILED。
     */
    public enum Status {
        QUEUED, PARSING, VALIDATING, VALIDATED, IMPORTING, DONE, PARTIAL, FAILED, CANCELLED
    }

    /** 错误行定位：行号从 2 起（1 是表头），精确到列。 */
    public record RowError(int row, String col, String value, String message) {
    }

    public final String id;
    public final Kind kind;
    public final String fileName;

    public volatile Status status = Status.QUEUED;
    public volatile int progress = 0;
    /** 当前阶段的可读描述（轮询界面直接展示）。 */
    public volatile String phase = "排队中";
    public volatile int totalRows = 0;
    public volatile int successRows = 0;
    public volatile int failedRows = 0;

    /** 校验/导入错误（上限 500 条，防止上万行全错时内存与响应爆炸）。 */
    public volatile List<RowError> errors = List.of();
    /** 校验通过的前 10 行（确认前的预览）。 */
    public volatile List<Map<String, String>> preview = List.of();
    /** 校验通过的全部行（确认导入的输入；导入开始后清空释放内存）。 */
    public volatile List<Map<String, String>> pendingRows = List.of();
    /** 导入失败的原行（部分成功后"重试失败行"的输入）。 */
    public volatile List<Map<String, String>> failedRowsData = List.of();

    /** 导出结果（xlsx 字节；导入任务恒为 null）。 */
    public volatile byte[] resultFile = null;
    public volatile String resultFileName = null;

    public volatile boolean cancelled = false;
    public final long createdAt = System.currentTimeMillis();

    /** 请求线程捕获的租户/部门上下文（异步线程恢复用，见服务实现）。 */
    public final long tenantId;
    public final Long deptId;

    public TransferTask(String id, Kind kind, String fileName, long tenantId, Long deptId) {
        this.id = id;
        this.kind = kind;
        this.fileName = fileName;
        this.tenantId = tenantId;
        this.deptId = deptId;
    }
}
