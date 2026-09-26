package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.application.tool.TransferTask;
import com.webadmin.application.tool.port.TransferTaskArchivePort;
import com.webadmin.infrastructure.persistence.mapper.ToolTransferTaskMapper;
import com.webadmin.infrastructure.persistence.po.ToolTransferTaskPO;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * 任务档案的 MyBatis-Plus 实现。
 *
 * <h3>upsert 为什么是「先 update 后 insert」</h3>
 * 档案的写入频率是：创建 1 次 insert + 工作线程收尾 1 次 update（终态），
 * update 是绝对多数路径；{@code updateById} 返回 0 行再 insert，
 * 避免每条档案都先 select 一次。同一任务只有单个工作线程写、
 * 轮询请求只读，不存在并发 upsert 竞争。
 *
 * <h3>租户隔离</h3>
 * 表不在 {@code TenantLineHandlerImpl} 的忽略名单里 —— 所有 SQL 由
 * 拦截器自动追加 {@code tenant_id = ?}，实现层不手写租户条件。
 * 这同时意味着：本 Port 只能在<b>带租户上下文</b>的调用链里使用
 * （AppService 的请求线程与虚拟线程都通过 {@code bindContext} 满足）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TransferTaskArchivePortImpl implements TransferTaskArchivePort {

    private final ToolTransferTaskMapper mapper;

    @Override
    public void upsert(ArchivedTask record) {
        ToolTransferTaskPO po = toPO(record);
        int updated;
        try {
            updated = mapper.updateById(po);
        } catch (Exception ex) {
            // update 侧的失败（约束/拦截器改写等）不放弃 —— 落到 insert 分支，
            // 失败原因也会经由 insert 的异常如实浮出，而不是静默丢失快照
            log.warn("档案 update 未成功（转 insert）id={}: {}", record.id(), ex.getMessage());
            updated = 0;
        }
        if (updated == 0) {
            try {
                mapper.insert(po);
            } catch (org.springframework.dao.DataAccessException ex) {
                // 行已存在（并发/时序错位，行内可能是旧快照）—— 重放一次 update
                // 保证读到本次快照；仍失败则抛给上层旁路日志
                int replayed = mapper.updateById(po);
                if (replayed == 0) {
                    throw ex;
                }
                log.info("档案经 insert 撞键后由 update 补写 id={}", record.id());
            }
        }
    }

    @Override
    public Optional<ArchivedTask> find(String id) {
        ToolTransferTaskPO po = mapper.selectById(id);
        return Optional.ofNullable(po).map(this::toRecord);
    }

    @Override
    public int purgeBefore(LocalDateTime deadline) {
        return mapper.delete(new LambdaQueryWrapper<ToolTransferTaskPO>()
                .lt(ToolTransferTaskPO::getCreateTime, deadline));
    }

    private ToolTransferTaskPO toPO(ArchivedTask record) {
        ToolTransferTaskPO po = new ToolTransferTaskPO();
        po.setId(record.id());
        po.setTenantId(record.tenantId());
        po.setDeptId(record.deptId());
        po.setKind(record.kind().name());
        po.setStatus(record.status().name());
        po.setPhase(record.phase());
        po.setFileName(record.fileName());
        po.setProgress(record.progress());
        po.setTotalRows(record.totalRows());
        po.setSuccessRows(record.successRows());
        po.setFailedRows(record.failedRows());
        po.setErrorSummary(record.errorSummary());
        // 审计字段显式赋值：项目的 MetaObjectHandler 只服务于带 fill 标记的
        // 既有实体，档案表不走全局填充 —— 不显式赋值 insert 会撞 NOT NULL
        LocalDateTime now = LocalDateTime.now();
        po.setCreateTime(now);
        po.setUpdateTime(now);
        po.setDelFlag(0L);
        po.setVersion(0);
        return po;
    }

    private ArchivedTask toRecord(ToolTransferTaskPO po) {
        try {
            return new ArchivedTask(
                    po.getId(),
                    po.getTenantId() == null ? 0L : po.getTenantId(),
                    po.getDeptId(),
                    TransferTask.Kind.valueOf(po.getKind()),
                    TransferTask.Status.valueOf(po.getStatus()),
                    po.getPhase() == null ? "" : po.getPhase(),
                    po.getFileName() == null ? "" : po.getFileName(),
                    po.getProgress() == null ? 0 : po.getProgress(),
                    po.getTotalRows() == null ? 0 : po.getTotalRows(),
                    po.getSuccessRows() == null ? 0 : po.getSuccessRows(),
                    po.getFailedRows() == null ? 0 : po.getFailedRows(),
                    po.getErrorSummary() == null ? "" : po.getErrorSummary());
        } catch (IllegalArgumentException ex) {
            // kind/status 被手工改坏：档案行不可信，按不存在处理
            log.warn("任务档案枚举值非法，按不存在处理 id={} kind={} status={}",
                    po.getId(), po.getKind(), po.getStatus());
            return null;
        }
    }
}
