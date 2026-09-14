package com.webadmin.application.iam.command;

import com.webadmin.domain.shared.TenantId;

/**
 * 租户续期命令。
 *
 * @param tenantId 目标租户
 * @param months   续期月数（正数）
 * @param planCode 续期后的套餐编码（可为空表示维持当前套餐）
 */
public record RenewTenantCommand(TenantId tenantId, int months, String planCode) {
}
