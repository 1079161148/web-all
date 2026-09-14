package com.webadmin.domain.iam.model.user;

import java.util.Set;

/**
 * 密码强度策略（领域规则）。
 *
 * <h3>为什么以"静态方法集合"而非值对象存在</h3>
 * 它校验的是<b>明文</b>，而明文只在"设置/修改密码"这一瞬间存在于内存中，
 * 不构成一个需要长期持有的领域概念 —— 造一个 {@code PlainPassword} 值对象
 * 反而会让明文在对象图里多活一会儿，增加被日志或异常带出去的风险。
 * 因此这里只提供"校验"这一项能力，不持有数据。
 *
 * <p>规则本身属于领域知识（"什么样的密码算合格"是业务决策，
 * 不是技术基础设施），因此放在领域层而非工具类里。
 * 是否启用、长度阈值等可配置项由应用层读取 {@code plt_config} 后传入。
 */
public final class PasswordPolicy {

    /** 常见弱密码片段：命中即拒绝，不区分大小写。 */
    private static final Set<String> COMMON_WEAK_PARTS = Set.of(
            "123456", "12345678", "password", "qwerty", "admin", "root",
            "111111", "000000", "abc123", "iloveyou", "88888888");

    private PasswordPolicy() {
    }

    /**
     * 校验明文密码强度。
     *
     * @param raw       明文密码
     * @param minLength 最小长度（来自平台配置，默认 8）
     * @param username  所属用户名，用于拒绝"密码包含用户名"
     * @throws IllegalArgumentException 不满足策略时抛出，消息即为可直接展示给用户的原因
     */
    public static void validate(String raw, int minLength, String username) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        int effectiveMin = minLength <= 0 ? 8 : minLength;
        if (raw.length() < effectiveMin) {
            throw new IllegalArgumentException("密码长度不能少于 " + effectiveMin + " 位");
        }
        if (raw.length() > 128) {
            throw new IllegalArgumentException("密码长度不能超过 128 位");
        }

        int categories = 0;
        if (raw.chars().anyMatch(Character::isLowerCase)) {
            categories++;
        }
        if (raw.chars().anyMatch(Character::isUpperCase)) {
            categories++;
        }
        if (raw.chars().anyMatch(Character::isDigit)) {
            categories++;
        }
        if (raw.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            categories++;
        }
        if (categories < 3) {
            throw new IllegalArgumentException("密码需包含大写字母、小写字母、数字、特殊字符中的至少三类");
        }

        String lower = raw.toLowerCase();
        if (username != null && !username.isBlank()
                && lower.contains(username.trim().toLowerCase())) {
            throw new IllegalArgumentException("密码不能包含用户名");
        }
        for (String weak : COMMON_WEAK_PARTS) {
            if (lower.contains(weak)) {
                throw new IllegalArgumentException("密码包含过于常见的弱口令片段，请更换");
            }
        }
    }
}
