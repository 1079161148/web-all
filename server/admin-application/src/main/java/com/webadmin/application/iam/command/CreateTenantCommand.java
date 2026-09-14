package com.webadmin.application.iam.command;

/**
 * 创建租户命令。
 *
 * <p>用 {@code record} 保证不可变 —— 命令对象一旦构造就不应被修改。
 *
 * <p>注意这里只携带 {@code planCode} 而非整个套餐对象：
 * 套餐的完整定义由应用层通过 {@code SubscriptionPlanRegistry} 端口解析，
 * 避免调用方伪造配额。
 *
 * @param code     租户编码（6~32 位小写字母数字连字符）
 * @param name     租户名称
 * @param planCode 套餐编码
 * @param remark   备注
 */
public record CreateTenantCommand(String code, String name, String planCode, String remark) {
}
