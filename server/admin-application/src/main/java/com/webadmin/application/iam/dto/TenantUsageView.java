package com.webadmin.application.iam.dto;

import java.time.Instant;
import java.util.List;

/**
 * 租户用量视图（用量看板的数据源）。
 *
 * <h3>为什么同时给出 initial / remaining / used 三个数</h3>
 * 只给"剩余"看不出来"总量是多少、用了多少"；只给"已用"又无法判断还能不能建。
 * 三件套一起给，前端才能画出"已用 / 总量"的进度条 —— 这是配额这类信息
 * 唯一直观的呈现方式。
 *
 * <p>{@code used = initial - remaining} 是<b>推导值</b>而不是独立存储：
 * 它天然与剩余量一致，不存在"两个数各记各的、某天对不上"的问题。
 *
 * @param effectiveStatus 有效状态（ACTIVE 但已过期时返回 EXPIRED，见聚合的脏读防护）
 * @param liveUsers       实时用户数（从用户表统计，而不是从配额推导 ——
 *                        两者可能不一致：历史数据、套餐变更等。给看板以实数为准）
 */
public record TenantUsageView(
        long tenantId,
        String tenantCode,
        String tenantName,
        String status,
        String effectiveStatus,
        String planCode,
        String planName,
        Instant expireTime,
        boolean expired,
        long liveUsers,
        List<QuotaDimension> quota) {

    /**
     * 单个配额维度。
     *
     * @param initial 套餐初始配额
     * @param used    已用量（initial - remaining）
     * @param remaining 剩余可用量
     */
    public record QuotaDimension(String type, String label, long initial, long used, long remaining) {
    }
}
