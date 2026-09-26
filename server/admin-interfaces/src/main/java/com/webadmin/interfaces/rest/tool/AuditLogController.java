package com.webadmin.interfaces.rest.tool;

import com.webadmin.application.audit.port.AuditLogPort;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 操作审计查询（只读）。
 *
 * diff 的语义：每行"字段: 旧值 → 新值"；CREATED 事件无 diff（summary 即全部信息）。
 * 审计本身只增不改不删 —— 该端点刻意不提供任何写操作。
 */
@Tag(name = "操作审计", description = "字段级变更审计（经领域事件异步落库）")
@RestController
@RequestMapping("/api/v1/tools/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogPort auditLogPort;

    @Operation(operationId = "pageAuditLogs", summary = "分页查询操作审计（最近优先）")
    @PreAuthorize("@ps.hasPermission('tools:audit:read')")
    @GetMapping
    public R<PageResult<AuditLogView>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<AuditLogPort.AuditLogEntry> result =
                auditLogPort.page(TenantContext.require(), page, size);
        return R.ok(new PageResult<>(
                result.records().stream().map(AuditLogView::from).toList(),
                result.total(), result.page(), result.size()));
    }

    public record AuditLogView(
            long id,
            String username,
            String action,
            String bizType,
            String bizId,
            String summary,
            String diffText,
            String createTime) {

        private static AuditLogView from(AuditLogPort.AuditLogEntry entry) {
            return new AuditLogView(
                    entry.id(),
                    entry.username(),
                    entry.action(),
                    entry.bizType(),
                    entry.bizId(),
                    entry.summary(),
                    entry.diffText(),
                    entry.createTime() == null ? ""
                            : entry.createTime().atZone(ZoneId.systemDefault())
                                    .toLocalDateTime().toString());
        }
    }
}