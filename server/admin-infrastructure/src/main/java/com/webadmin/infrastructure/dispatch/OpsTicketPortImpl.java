package com.webadmin.infrastructure.dispatch;

import com.webadmin.application.dispatch.port.OpsTicketPort;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.infrastructure.persistence.mapper.OpsTicketMapper;
import com.webadmin.infrastructure.persistence.po.OpsTicketPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 工单端口实现（惰性 SLA 升级在这里：查询前先把过期 PENDING 推进为 ESCALATED）。 */
@Component
@RequiredArgsConstructor
public class OpsTicketPortImpl implements OpsTicketPort {

    private final OpsTicketMapper mapper;

    @Override
    public List<OpsTicket> list(long tenantId) {
        mapper.escalateOverdue(tenantId, LocalDateTime.now());
        return mapper.selectRecent(tenantId).stream()
                .map(OpsTicketPortImpl::toTicket)
                .toList();
    }

    @Override
    public boolean claim(long id, long tenantId, String claimer) {
        return mapper.claim(id, tenantId, claimer) == 1;
    }

    @Override
    public boolean complete(long id, long tenantId, String claimer) {
        return mapper.complete(id, tenantId, claimer) == 1;
    }

    @Override
    public boolean release(long id, long tenantId, String claimer) {
        return mapper.release(id, tenantId, claimer) == 1;
    }

    private static OpsTicket toTicket(OpsTicketPO po) {
        return new OpsTicket(
                po.getId(),
                po.getTitle(),
                po.getChannel(),
                po.getPriority(),
                po.getState(),
                po.getClaimer(),
                po.getSlaMinutes(),
                po.getCreatedAt() == null ? 0L
                        : po.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        );
    }
}
