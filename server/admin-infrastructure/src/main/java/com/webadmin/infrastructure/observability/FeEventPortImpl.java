package com.webadmin.infrastructure.observability;

import com.webadmin.application.observability.port.FeEventPort;
import com.webadmin.infrastructure.persistence.mapper.FeEventMapper;
import com.webadmin.infrastructure.persistence.po.FeEventPO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 前端可观测事件端口实现（表：fe_event，批量追加写）。 */
@Component
@RequiredArgsConstructor
public class FeEventPortImpl implements FeEventPort {

    private final FeEventMapper mapper;

    @Override
    public void saveAll(long tenantId, Long userId, List<IncomingEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<FeEventPO> rows = events.stream().map(event -> {
            FeEventPO po = new FeEventPO();
            po.setTenantId(tenantId);
            po.setUserId(userId);
            po.setType(event.type());
            po.setName(event.name());
            po.setPage(event.page());
            po.setDurationMs(event.durationMs());
            po.setDetail(event.detail());
            return po;
        }).toList();
        mapper.insertBatch(tenantId, userId, rows);
    }

    @Override
    public Summary summary(long tenantId) {
        List<SlowApi> slowApis = mapper.selectSlowApis(tenantId).stream()
                .map(row -> new SlowApi(
                        (String) row.get("name"),
                        ((Number) row.get("calls")).longValue(),
                        ((Number) row.get("avgMs")).doubleValue(),
                        ((Number) row.get("maxMs")).longValue()))
                .toList();
        List<ErrorItem> errors = mapper.selectRecentErrors(tenantId).stream()
                .map(row -> new ErrorItem(
                        (String) row.get("name"),
                        (String) row.get("page"),
                        (String) row.get("detail"),
                        toDateTime(row.get("createdAt"))))
                .toList();
        List<PageView> topPages = mapper.selectTopPages(tenantId).stream()
                .map(row -> new PageView(
                        (String) row.get("name"),
                        ((Number) row.get("views")).longValue()))
                .toList();
        return new Summary(slowApis, errors, topPages);
    }

    private static java.time.LocalDateTime toDateTime(Object value) {
        return value == null ? null
                : ((java.time.LocalDateTime) value);
    }
}
