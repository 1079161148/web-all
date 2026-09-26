package com.webadmin.application.audit;

import com.webadmin.application.audit.port.AuditLogPort;
import com.webadmin.application.survey.dto.SurveyTaskDTO;
import com.webadmin.application.survey.event.SurveyTaskChangedEvent;
import com.webadmin.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 审计事件消费者：把"业务变更"翻译成"字段级 diff"并落审计。
 *
 * <h3>diff 在消费者而不是发布方计算</h3>
 * 发布方只提供 before/after 快照 —— 它不该知道审计的展示格式；
 * 将来要"只记录被关注字段"或"值脱敏"，改这里即可。
 *
 * <h3>幂等性（Outbox 消费者的硬约束）</h3>
 * 处理失败会重试，同一事件可能被消费多次。重放产生的 diff 完全相同 ——
 * 审计侧接受少量重复，换取"事件必达"的保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventHandler {

    private final AuditLogPort auditLogPort;

    @ApplicationModuleListener
    public void on(SurveyTaskChangedEvent event) {
        java.util.Optional<Long> previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            String diffText = event.before() == null ? null : buildDiff(event.before(), event.after());
            String summary = "CREATED".equals(event.action())
                    ? String.format("创建调研任务「%s」", name(event.after()))
                    : String.format("修改调研任务「%s」（%d 个字段变更）",
                            name(event.after()), countChanges(event.before(), event.after()));
            auditLogPort.record(new AuditLogPort.AuditLogEntry(
                    0L,
                    event.tenantId(),
                    event.userId(),
                    event.username(),
                    event.action(),
                    "调研任务",
                    String.valueOf(event.after().id()),
                    summary,
                    diffText,
                    Instant.now()));
        } catch (Exception ex) {
            // 审计失败不反噬业务：记日志并重抛，交由 Outbox 重试机制兜底
            log.error("审计写入失败 action={}", event.action(), ex);
            throw ex instanceof RuntimeException runtimeEx ? runtimeEx : new IllegalStateException(ex);
        } finally {
            // 调度线程的上下文直接清除（本消费者不依赖恢复前的值）
            TenantContext.clear();
        }
    }

    private static String name(SurveyTaskDTO dto) {
        return dto == null ? "" : dto.taskName();
    }

    static String buildDiff(SurveyTaskDTO before, SurveyTaskDTO after) {
        Map<String, String[]> changes = new LinkedHashMap<>();
        buildDiffInto(changes, before, after);
        StringJoiner joiner = new StringJoiner("\n");
        changes.forEach((field, pair) -> joiner.add(String.format("%s: %s → %s", field, pair[0], pair[1])));
        return joiner.toString();
    }

    static int countChanges(SurveyTaskDTO before, SurveyTaskDTO after) {
        Map<String, String[]> changes = new LinkedHashMap<>();
        buildDiffInto(changes, before, after);
        return changes.size();
    }

    private static void buildDiffInto(Map<String, String[]> changes, SurveyTaskDTO before, SurveyTaskDTO after) {
        collect(changes, "任务名称", before.taskName(), after.taskName());
        collect(changes, "任务类型", before.taskType(), after.taskType());
        collect(changes, "优先级", before.priority(), after.priority());
        collect(changes, "负责人", before.ownerName(), after.ownerName());
        collect(changes, "开始日期", text(before.startDate()), text(after.startDate()));
        collect(changes, "结束日期", text(before.endDate()), text(after.endDate()));
        collect(changes, "进度", text(before.progress()), text(after.progress()));
        collect(changes, "状态", before.status(), after.status());
        collect(changes, "备注", before.remark(), after.remark());
    }

    private static void collect(Map<String, String[]> changes, String field, String before, String after) {
        String b = before == null ? "" : before;
        String a = after == null ? "" : after;
        if (!b.equals(a)) {
            changes.put(field, new String[]{b.isEmpty() ? "（空）" : b, a.isEmpty() ? "（空）" : a});
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        return String.valueOf(value);
    }
}
