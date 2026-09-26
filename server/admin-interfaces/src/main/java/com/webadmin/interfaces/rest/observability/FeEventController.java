package com.webadmin.interfaces.rest.observability;

import com.webadmin.application.observability.FeEventService;
import com.webadmin.application.observability.port.FeEventPort;
import com.webadmin.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端可观测（采集 + 聚合视图）。
 *
 * <p>上报端点面向<b>所有登录用户</b>（事件的生产者就是全体用户，见
 * {@link FeEventService}）；summary 面向管理者，需要显式权限码。
 */
@Tag(name = "前端可观测", description = "前端错误 / 接口耗时 / 路由耗时 / 性能体征的采集与聚合")
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
public class FeEventController {

    private final FeEventService eventService;

    public record IngestRequest(
            @NotEmpty List<IngestItem> events) {
    }

    public record IngestItem(
            String type,
            String name,
            String page,
            Integer durationMs,
            String detail) {
    }

    @Operation(operationId = "ingestFeEvents", summary = "批量上报前端事件（≤50 条/批）",
            description = "type ∈ api/route/error/vital；userId 由令牌解析，前端传什么都不采信")
    @PostMapping("/events")
    public R<Void> ingest(@Valid @RequestBody IngestRequest request) {
        List<FeEventService.IncomingEvent> events = request.events().stream()
                .map(item -> new FeEventService.IncomingEvent(
                        item.type(), item.name(), item.page(), item.durationMs(), item.detail()))
                .toList();
        eventService.ingest(events);
        return R.ok();
    }

    @Operation(operationId = "getObservabilitySummary", summary = "聚合视图",
            description = "慢接口 Top10（近 1 小时按平均耗时）/ 最近 50 条前端错误 / 页面访问 Top10（近 1 天）")
    @PreAuthorize("@ps.hasPermission('tools:observability:read')")
    @GetMapping("/summary")
    public R<FeEventPort.Summary> summary() {
        return R.ok(eventService.summary());
    }
}
