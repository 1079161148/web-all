package com.webadmin.application.tool.port;

import com.webadmin.application.tool.TransferTask;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 数据传输任务的档案存取。
 *
 * <h3>角色边界：档案，不是热状态</h3>
 * 任务的权威状态仍在 {@code DataTransferAppService} 的内存里（轮询接口读内存，
 * 毫秒级新鲜度）。这张档案表解决的是内存的三块短板：
 * <ol>
 *   <li>重启丢现场 —— 重启后 {@code get()} 回落查表，任务仍有据可查；</li>
 *   <li>历史审计 —— 谁、何时、导入了什么、结果如何；</li>
 *   <li>存储治理 —— 过期档案按时间清理。</li>
 * </ol>
 *
 * <h3>为什么接口只有四个方法、没有「启动恢复」</h3>
 * 租户拦截器在上下文缺失时快速失败（{@code TenantContext.require()}），
 * 因此启动钩子 / 系统级定时任务里的 SQL 要么得循环租户、要么得放宽隔离。
 * 实际语义上也不需要：
 * <ul>
 *   <li><b>重启中断</b>不改由启动钩子落库，而是读取时判定 —— 档案里残留的
 *       非终态行必然已被重启打断，{@code find()} 的调用方（请求线程，带租户
 *       上下文）负责把它翻译成 FAILED；</li>
 *   <li><b>清理</b>由创建任务时的请求线程顺带执行（见 AppService 的
 *       每自然日一次节流），天然带租户上下文与隔离。</li>
 * </ul>
 */
public interface TransferTaskArchivePort {

    /**
     * 写入/更新一条档案（按任务 id upsert）。
     *
     * <p>调用方约定：实现不得抛出 —— 档案是旁路，写失败只允许记日志，
     * 不能影响任务本身。
     */
    void upsert(ArchivedTask record);

    /** 按任务 id 查档案（租户隔离由拦截器保证）。 */
    Optional<ArchivedTask> find(String id);

    /** 删除指定时间之前创建的档案（本租户），返回删除行数。 */
    int purgeBefore(LocalDateTime deadline);

    /**
     * 档案行：只含元数据快照。
     *
     * <p>工作数据（校验行、错误明细、结果文件）有意不进档案 —— 它们
     * 分钟级过期且可能很大，落库只会制造一张又慢又危险的表。
     */
    record ArchivedTask(
            String id,
            long tenantId,
            Long deptId,
            TransferTask.Kind kind,
            TransferTask.Status status,
            String phase,
            String fileName,
            int progress,
            int totalRows,
            int successRows,
            int failedRows,
            String errorSummary) {
    }
}
