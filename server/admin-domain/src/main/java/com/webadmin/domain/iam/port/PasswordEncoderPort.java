package com.webadmin.domain.iam.port;

import com.webadmin.domain.iam.model.user.PasswordHash;

/**
 * 密码加密端口（依赖倒置）。
 *
 * <h3>为什么它必须是端口而不是直接调用 BCrypt</h3>
 * <ul>
 *   <li><b>算法会换</b>：今天是 BCrypt，明天可能因合规要求换成 Argon2id。
 *       领域层不该因为换算法而改动</li>
 *   <li><b>参数要可配</b>：BCrypt 的 cost 因子需要随硬件提升而上调（8 → 10 → 12），
 *       这是部署决策而非领域规则</li>
 *   <li><b>旧哈希要能平滑迁移</b>：真实项目里常需要"登录成功时用新参数重新加密"，
 *       这要求 {@code matches} 与 {@code encode} 是可分别替换的</li>
 * </ul>
 *
 * <p>注意 {@link #matches} 的签名：它接收<b>明文</b>与<b>哈希</b>。
 * 这看起来"让明文在领域层过了一手"，但无法避免 —— 校验密码这件事语义上
 * 就需要两者同时在场。真正要防的是"明文被长期持有或被日志带出"，
 * 所以这里只做即时校验、不返回也不存储明文。
 */
public interface PasswordEncoderPort {

    /** 把明文密码加密为哈希。 */
    PasswordHash encode(String rawPassword);

    /** 校验明文密码是否匹配哈希。 */
    boolean matches(String rawPassword, PasswordHash encoded);

    /**
     * 判断指定哈希是否需要用当前参数重新加密（cost 升级等）。
     *
     * <p>返回 {@code true} 时，调用方（认证流程）应在登录成功后
     * 用 {@link #encode} 重新生成并保存，实现哈希的平滑升级。
     */
    boolean needsRehash(PasswordHash encoded);
}
