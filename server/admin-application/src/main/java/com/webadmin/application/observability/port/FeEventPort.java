package com.webadmin.application.observability.port;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 前端可观测事件端口（采集 → 落库 → 聚合）。
 *
 * <h3>边界：只收"事件"，不做"判断"</h3>
 * 什么算慢、什么算异常页面，是<b>查看者</b>在 summary 侧关心的；
 * 写入侧只负责如实记录 —— 写入时过滤"看起来有问题"的事件，
 * 会让阈值变更时历史数据无法重算。
 */
public interface FeEventPort {

    enum Type {
        /** 接口请求耗时（Resource Timing）。 */
        API,
        /** 路由切换耗时。 */
        ROUTE,
        /** 前端错误（Vue errorHandler / window error / unhandledrejection）。 */
        ERROR,
        /** 性能体征（LCP / CLS）。 */
        VITAL
    }

    /** 一条待写入事件（userId 由服务端从令牌解析，前端不传 —— 防伪造）。 */
    record IncomingEvent(
            String type,
            String name,
            String page,
            Integer durationMs,
            String detail
    ) {
    }

    /** 写入一批事件（已通过应用层校验）。 */
    void saveAll(long tenantId, Long userId, List<IncomingEvent> events);

    /** 慢接口（近 1 小时按平均耗时排序）。 */
    record SlowApi(String name, long calls, double avgMs, long maxMs) {
    }

    /** 一条前端错误。 */
    record ErrorItem(String name, String page, String detail, LocalDateTime createdAt) {
    }

    /** 页面访问排行（近 1 天）。 */
    record PageView(String page, long views) {
    }

    record Summary(List<SlowApi> slowApis, List<ErrorItem> errors, List<PageView> topPages) {
    }

    /** 聚合视图（写入侧不做的判断在这里做）。 */
    Summary summary(long tenantId);
}
