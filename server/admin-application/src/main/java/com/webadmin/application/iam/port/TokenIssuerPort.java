package com.webadmin.application.iam.port;

import com.webadmin.application.security.CurrentUser;
import java.util.Set;

/**
 * 令牌签发端口。
 *
 * <h3>为什么需要这个端口（而不是让 AuthAppService 直接调用 JwtTokenProvider）</h3>
 * {@code JwtTokenProvider} 实现位于 infrastructure。若应用服务直接依赖它，
 * 就形成了 <b>application → infrastructure</b> 的反向依赖 ——
 * 这正是 ArchUnit 会拦下的红线（{@code application_should_not_depend_on_infrastructure}）。
 *
 * <p>更深层的原因：<b>应用层不该知道令牌是什么格式</b>。
 * 今天是 JWT，明天可能因为要支持"服务端会话 + 可撤销令牌"而换成不透明令牌（opaque token），
 * 或引入 Spring Authorization Server 由它托管。届时只需换实现，
 * 认证编排流程（校验密码 → 计数失败 → 签发 → 记录登录）一行都不用改。
 *
 * <p>这也是"依赖倒置"在真实项目里最常被用到的地方 ——
 * 不是为了架构图好看，而是为了让这种替换成为可能。
 */
public interface TokenIssuerPort {

    /**
     * 签发访问令牌。
     *
     * @param tokenVersion 签发时的令牌版本号，会被写进令牌（{@code ver} 声明）。
     *                     校验方将其与用户当前版本比对，不一致即失效 ——
     *                     这是"改密码/停用/强制下线立即踢人"的实现基础。
     *                     <b>必须</b>由调用方传入而不是由实现自行查询：
     *                     登录流程刚保存过聚合，手里就是最新值，
     *                     实现再查一次既浪费 also 可能读到过期缓存
     */
    IssuedToken issue(CurrentUser user, Set<String> roleKeys, long tokenVersion);

    /**
     * 签发结果。
     *
     * @param accessToken      令牌字符串
     * @param expiresInSeconds 有效期（秒）。返回给前端用于提前刷新，
     *                         <b>不要</b>让前端自己解析 JWT 的 exp 来算 —— 那会把令牌格式泄漏到前端
     */
    record IssuedToken(String accessToken, long expiresInSeconds) {
    }
}
