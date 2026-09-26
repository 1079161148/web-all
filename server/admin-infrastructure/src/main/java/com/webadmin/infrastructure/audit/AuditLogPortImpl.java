package com.webadmin.infrastructure.audit;

import com.webadmin.application.audit.port.AuditLogPort;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.AuditLogMapper;
import com.webadmin.infrastructure.persistence.po.AuditLogPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 审计端口实现（MyBatis-Plus；写入经事件处理器调用，幂等性由业务侧保证）。 */
@Component
@RequiredArgsConstructor
public class AuditLogPortImpl implements AuditLogPort {

    private final AuditLogMapper mapper;

    @Override
    public void record(AuditLogEntry entry) {
        mapper.insert(AuditLogPO.of(entry.tenantId(), entry.userId(), entry.username(),
                entry.action(), entry.bizType(), entry.bizId(), entry.summary(), entry.diffText()));
    }

    @Override
    public PageResult<AuditLogEntry> page(long tenantId, int page, int size) {
        long total = mapper.countByTenant(tenantId);
        List<AuditLogEntry> records = mapper
                .selectPage(tenantId, size, (Math.max(1, page) - 1) * size)
                .stream()
                .map(AuditLogPortImpl::toEntry)
                .toList();
        return PageResult.of(records, total, page, size);
    }

    private static AuditLogEntry toEntry(AuditLogPO po) {
        return new AuditLogEntry(
                po.getId(),
                po.getTenantId(),
                po.getUserId(),
                po.getUsername(),
                po.getAction(),
                po.getBizType(),
                po.getBizId(),
                po.getSummary(),
                po.getDiffText(),
                po.getCreateTime() == null
                        ? null
                        : po.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant()
        );
    }
}
