package com.webadmin.interfaces.rest.dispatch;

import com.webadmin.application.dispatch.OpsTicketService;
import com.webadmin.application.dispatch.port.OpsTicketPort;
import com.webadmin.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单调度后端（抢单的真实并发演示）。
 *
 * 抢单的原子性在端口层的条件 UPDATE（数据库行锁）——
 * 两个用户同时抢单，只有一个成功，另一个收到"已被他人抢走"。
 * 前端开两个标签页即可复现真实竞争。
 */
@Tag(name = "工单调度", description = "工单列表 / 原子抢单 / 办结")
@RestController
@RequestMapping("/api/v1/dispatch/tickets")
@RequiredArgsConstructor
public class OpsTicketController {

    private final OpsTicketService ticketService;

    @Operation(operationId = "listOpsTickets", summary = "工单列表（最近 100 条，含惰性 SLA 升级）")
    @PreAuthorize("@ps.hasPermission('tools:dispatch:manage')")
    @GetMapping
    public R<List<OpsTicketPort.OpsTicket>> list() {
        return R.ok(ticketService.list());
    }

    @Operation(operationId = "claimOpsTicket", summary = "抢单（原子）",
            description = "条件 UPDATE 保证原子性：非 PENDING 状态一律失败并返回明确错误")
    @PreAuthorize("@ps.hasPermission('tools:dispatch:manage')")
    @PostMapping("/{id}/claim")
    public R<Void> claim(@PathVariable long id) {
        ticketService.claim(id);
        return R.ok();
    }

    @Operation(operationId = "completeOpsTicket", summary = "办结（仅接单人）")
    @PreAuthorize("@ps.hasPermission('tools:dispatch:manage')")
    @PostMapping("/{id}/complete")
    public R<Void> complete(@PathVariable long id) {
        ticketService.complete(id);
        return R.ok();
    }

    @Operation(operationId = "releaseOpsTicket", summary = "放回待处理池（仅接单人）",
            description = "看板\"处理中 → 待处理\"拖拽的后端语义。条件 UPDATE：非本人或"
                    + "非 CLAIMED 状态一律失败")
    @PreAuthorize("@ps.hasPermission('tools:dispatch:manage')")
    @PostMapping("/{id}/release")
    public R<Void> release(@PathVariable long id) {
        ticketService.release(id);
        return R.ok();
    }
}