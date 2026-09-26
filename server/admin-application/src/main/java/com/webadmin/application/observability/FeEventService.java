package com.webadmin.application.observability;

import com.webadmin.application.observability.port.FeEventPort;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 前端可观测事件的应用服务。
 *
 * <h3>上报是"所有登录用户"的能力</h3>
 * 与业务权限不同：观测数据的产生者就是全体用户，按权限码裁剪反而
 * 让大多数用户的报错永远收不上来。身份仍由令牌解析（userId 服务端填，
 * 前端传什么都不采信），读侧（summary）才需要权限码。
 *
 * <h3>批量与上限</h3>
 * 客户端 10s/20 条批量上报；单批上限 50 —— 超过说明客户端实现有 bug
 * （或被滥用），快速失败而不是默默收下，问题才会暴露。
 */
@Service
@RequiredArgsConstructor
public class FeEventService {

    private static final int MAX_BATCH = 50;

    private static final int MAX_NAME = 120;
    private static final int MAX_PAGE = 200;
    private static final int MAX_DETAIL = 500;

    private static final Set<String> TYPES = Set.of("api", "route", "error", "vital");

    private final FeEventPort eventPort;
    private final CurrentUserPort currentUserPort;

    public void ingest(List<IncomingEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "事件列表不能为空");
        }
        if (events.size() > MAX_BATCH) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "单批事件数超限（最多 " + MAX_BATCH + "）");
        }
        List<FeEventPort.IncomingEvent> normalized = new ArrayList<>(events.size());
        for (IncomingEvent event : events) {
            String type = event.type() == null ? "" : event.type().toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "未知事件类型：" + type);
            }
            String name = requireBounded(event.name(), MAX_NAME, "name");
            String page = bound(event.page(), MAX_PAGE);
            String detail = bound(event.detail(), MAX_DETAIL);
            Integer duration = event.durationMs();
            if (duration != null && (duration < 0 || duration > 600_000)) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "durationMs 超出合理范围（0~600000）");
            }
            normalized.add(new FeEventPort.IncomingEvent(type, name, page, duration, detail));
        }
        var current = currentUserPort.requireCurrentUser();
        eventPort.saveAll(TenantContext.require(), current.userId(), normalized);
    }

    public FeEventPort.Summary summary() {
        return eventPort.summary(TenantContext.require());
    }

    /** 前端上报事件（type/name/page/durationMs/detail，userId 由服务端解析）。 */
    public record IncomingEvent(
            String type,
            String name,
            String page,
            Integer durationMs,
            String detail
    ) {
    }

    private static String requireBounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, field + " 不能为空");
        }
        if (value.length() > max) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    field + " 超长（最多 " + max + " 字符）");
        }
        return value;
    }

    private static String bound(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
