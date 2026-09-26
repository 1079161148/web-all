package com.webadmin.application.iam.job;

import com.webadmin.application.iam.TenantAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 租户到期推进任务（自动冻结）。
 *
 * <h3>为什么冻结不依赖本任务</h3>
 * 「过期租户不可用」由两道独立的防线保证：
 * <ol>
 *   <li><b>实时判定</b>（登录校验、配额拦截里的 {@code assertAccessible}）——
 *       按有效期即时计算，调度延迟甚至调度器宕机都拦得住</li>
 *   <li><b>状态落库</b>（本任务）—— 把 EXPIRED 写进状态字段，
 *       供列表/报表展示，并触发「强制下线」副作用（过期事件 → 版本号批量提升）</li>
 * </ol>
 * 第 1 层是安全防线，第 2 层是运营闭环。<b>不要把两者合并成一个</b>：
 * 只靠定时任务冻结，宕机窗口内过期租户照常登录；只靠实时判定，
 * 在线用户的旧令牌会一直活到自然过期。
 *
 * <h3>频率取舍</h3>
 * 每小时整点。到期判断本身是实时的（第 1 层），
 * 本任务的价值在"踢下线"副作用，小时级延迟对管理场景可接受；
 * 更高的频率只是空转。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantExpirationJob {

    private final TenantAppService tenantAppService;

    @Scheduled(cron = "0 0 * * * *")
    public void expireDueTenants() {
        try {
            int count = tenantAppService.expireDueTenants();
            if (count > 0) {
                log.info("[定时任务] 到期推进完成：本批冻结 {} 个租户", count);
            }
        } catch (Exception ex) {
            // 定时任务抛异常只会留下一行日志，吞掉异常让下一周期重试
            log.error("[定时任务] 到期推进失败（下一周期重试）", ex);
        }
    }
}
