package com.webadmin.application.dispatch;

import com.webadmin.application.dispatch.port.OpsTicketPort;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工单调度应用服务。
 *
 * <p>"抢单"的真实并发由端口层的条件 UPDATE 保证（见 {@link OpsTicketPort}）；
 * 本服务只负责把当前用户身份带上、把"抢不到"翻译成明确的业务错误。
 */
@Service
@RequiredArgsConstructor
public class OpsTicketService {

    private final OpsTicketPort ticketPort;
    private final CurrentUserPort currentUserPort;

    public List<OpsTicketPort.OpsTicket> list() {
        return ticketPort.list(TenantContext.require());
    }

    public void claim(long ticketId) {
        var current = currentUserPort.requireCurrentUser();
        boolean claimed = ticketPort.claim(ticketId, TenantContext.require(), current.username());
        if (!claimed) {
            throw new BizException(CommonErrorCode.OPERATION_NOT_ALLOWED, "手慢了：该工单已被他人抢走");
        }
    }

    public void complete(long ticketId) {
        var current = currentUserPort.requireCurrentUser();
        boolean done = ticketPort.complete(ticketId, TenantContext.require(), current.username());
        if (!done) {
            throw new BizException(CommonErrorCode.OPERATION_NOT_ALLOWED,
                    "只有该工单的接单人可以办结");
        }
    }

    /** 放回待处理池（看板"处理中 → 待处理"拖拽）。 */
    public void release(long ticketId) {
        var current = currentUserPort.requireCurrentUser();
        boolean released = ticketPort.release(ticketId, TenantContext.require(), current.username());
        if (!released) {
            throw new BizException(CommonErrorCode.OPERATION_NOT_ALLOWED,
                    "只有该工单的接单人可以放回");
        }
    }
}
