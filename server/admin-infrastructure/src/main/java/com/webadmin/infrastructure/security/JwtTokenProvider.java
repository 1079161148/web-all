package com.webadmin.infrastructure.security;

import com.webadmin.application.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与校验。
 *
 * <h3>为什么把权限与角色放进令牌</h3>
 * 好处：鉴权时无需查库（{@code PermissionResolver} 只作为 {@code @PreAuthorize}
 * 里显式权限码的判定来源，而 Spring Security 的角色闸门读令牌即可）。
 * 代价是<b>令牌签发后权限变更不会立即生效</b>。
 *
 * <h3>因此本实现有意只放最小必要信息</h3>
 * 令牌里只放 {@code userId} / {@code tenantId} / {@code username} / {@code roles}，
 * <b>不放权限码集合</b>。原因：权限码有几百个，放进令牌会让每个请求头膨胀到几 KB，
 * 而且权限变更的生效延迟会从"缓存 TTL"变成"令牌有效期"，运维排查会非常痛苦。
 * 角色标识只有几个，放进去代价很小，能让 {@code hasRole} 类判断零查询。
 *
 * <p>真正的权限判定走 {@link com.webadmin.application.iam.security.PermissionResolver}，
 * 它有 Redis 缓存且支持精确失效 —— 权限改动后立刻生效（见 {@code RoleEvent} 的处理器）。
 *
 * <h3>算法选择</h3>
 * 用 HS256（对称密钥）。生产若需要让多个服务独立校验令牌而互不持有密钥，
 * 应换成 RS256（非对称）。当前的对称方案意味着<b>能签发就能校验</b>，
 * 因此密钥必须只掌握在认证服务手中。
 */
@Component
public class JwtTokenProvider implements com.webadmin.application.iam.port.TokenIssuerPort {

    /** HS256 要求密钥至少 32 字节（256 位），否则 Nimbus 会直接抛异常。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration accessTokenTtl;
    private final String issuer;

    public JwtTokenProvider(
            @Value("${webadmin.security.jwt.secret}") String secret,
            @Value("${webadmin.security.jwt.access-token-ttl:PT30M}") Duration accessTokenTtl,
            @Value("${webadmin.security.jwt.issuer:webadmin}") String issuer) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // 快速失败而不是"用短密钥凑合"：短密钥的 HS256 可被暴力破解，
            // 而这个问题在启动期暴露的成本远低于上线后暴露。
            throw new IllegalStateException(
                    "JWT 密钥长度不足：HS256 要求至少 " + MIN_SECRET_BYTES
                            + " 字节（当前 " + keyBytes.length + "）。"
                            + "请通过环境变量 webadmin.security.jwt.secret 注入足够长的随机串");
        }
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.accessTokenTtl = accessTokenTtl;
        this.issuer = issuer;
    }

    /**
     * 实现应用层端口：签发访问令牌。
     *
     * <p>返回 {@code IssuedToken} 而不是裸字符串，是为了把有效期一并交给调用方 ——
     * 前端需要在令牌过期前主动刷新，而计算过期时间<b>不应该由前端解析 JWT 完成</b>
     * （那会把令牌格式泄漏给前端，一旦换成不透明令牌前端就要改）。
     */
    @Override
    public IssuedToken issue(CurrentUser user, Set<String> roleKeys) {
        return new IssuedToken(issueAccessToken(user, roleKeys), accessTokenTtlSeconds());
    }

    /** 签发访问令牌（内部实现，返回裸字符串）。 */
    public String issueAccessToken(CurrentUser user, Set<String> roleKeys) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .subject(String.valueOf(user.userId()))
                .claim("userId", user.userId())
                .claim("tenantId", user.tenantId())
                .claim("username", user.username())
                .claim("roles", roleKeys)
                .build();
        // ⚠️ 必须显式指定算法头，否则 NimbusJwtEncoder 会尝试为默认算法（RS256）
        //    在密钥源里找匹配的 JWK，而 ImmutableSecret 提供的是对称密钥（oct），
        //    于是抛 "Failed to select a JWK signing key" —— 报错信息完全没提算法不匹配，
        //    很难从字面猜到根因（实测踩过）。
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /**
     * 校验并解析令牌。
     *
     * @throws org.springframework.security.oauth2.jwt.JwtException 签名错误 / 已过期 / 结构非法
     */
    public Jwt decode(String token) {
        return decoder.decode(token);
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }
}
