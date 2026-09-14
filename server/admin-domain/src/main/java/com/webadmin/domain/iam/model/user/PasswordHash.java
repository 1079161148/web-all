package com.webadmin.domain.iam.model.user;

import java.util.Set;

/**
 * 密码哈希（值对象）。
 *
 * <h3>刻意不做的事</h3>
 * <ul>
 *   <li><b>不在这里做加密</b>：BCrypt 是基础设施能力（算法、cost 参数、未来可能换 Argon2id），
 *       通过 {@code PasswordEncoder} 端口注入。领域层只持有"已加密的结果"</li>
 *   <li><b>不提供 {@code toString()} 输出明文</b>：见下方覆盖</li>
 *   <li><b>不校验强度</b>：强度规则作用于**明文**，而值对象从构造起就只有密文，
 *       拿不到明文自然无法校验。强度校验在应用层用 {@link PasswordPolicy} 对明文做</li>
 * </ul>
 *
 * <p>这几条合起来说明一个原则：<b>值对象的职责边界由"它手上有什么数据"决定，
 * 而不是由"业务上好像应该在这里做"决定。</b>
 */
public record PasswordHash(String value) {

    /** 已知的弱哈希前缀黑名单：防止把明文密码直接当哈希存进来。 */
    private static final Set<String> FORBIDDEN = Set.of("123456", "password", "admin", "");

    public PasswordHash {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("密码哈希不能为空");
        }
        // 防范"误把明文当哈希传入"这类低级但致命的错误：
        // BCrypt 哈希固定以 $2a$ / $2b$ / $2y$ 开头且长度 60
        if (!value.startsWith("$2")) {
            throw new IllegalArgumentException(
                    "密码哈希格式不合法：应为 BCrypt 哈希（以 $2a$/$2b$/$2y$ 开头）");
        }
        if (FORBIDDEN.contains(value)) {
            throw new IllegalArgumentException("检测到疑似明文密码被当作哈希传入");
        }
    }

    public static PasswordHash of(String value) {
        return new PasswordHash(value);
    }

    /** 从持久化还原。 */
    public static PasswordHash ofPersisted(String value) {
        return new PasswordHash(value);
    }

    /**
     * 禁止哈希出现在日志里。
     *
     * <p>哈希虽不可逆，但泄露它等于把离线爆破的难度从"穷举密码空间"降到
     * "跑一遍字典 + GPU 加速"。日志、异常栈、监控埋点都会调用 toString，
     * 在这里堵住是最省事的一道防线。
     */
    @Override
    public String toString() {
        return "PasswordHash[PROTECTED]";
    }
}
