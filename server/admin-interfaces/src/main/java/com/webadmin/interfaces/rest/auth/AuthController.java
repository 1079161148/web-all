package com.webadmin.interfaces.rest.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.webadmin.application.iam.AuthAppService;
import com.webadmin.application.iam.CaptchaAppService;
import com.webadmin.application.iam.RegisterAppService;
import com.webadmin.application.iam.command.LoginCommand;
import com.webadmin.application.iam.command.RegisterCommand;
import com.webadmin.application.iam.dto.CurrentUserDTO;
import com.webadmin.application.iam.dto.LoginResult;
import com.webadmin.application.iam.dto.MenuDTO;
import com.webadmin.common.api.R;
import com.webadmin.interfaces.rest.auth.request.LoginRequest;
import com.webadmin.interfaces.rest.interceptor.RateLimit;
import com.webadmin.interfaces.rest.auth.response.SessionViewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <h3>本 Controller 刻意没有任何 {@code @PreAuthorize}</h3>
 * 因为它是"获取权限"的入口本身：
 * <ul>
 *   <li>{@code /login} 必须在未认证时可用（已加入 {@code SecurityConfig} 的公开清单）</li>
 *   <li>{@code /me}、{@code /menus}、{@code /permissions} 只要求"已认证"，
 *       不要求具体权限码 —— 任何登录用户都需要读取自己的身份与菜单，
 *       否则前端无法完成动态路由装配</li>
 * </ul>
 * <b>但"不需要权限码"不等于"不需要认证"</b>：这三个接口都会通过
 * {@code currentUserPort.requireCurrentUser()} 强制要求登录上下文。
 *
 * <h3>⚠️ 为什么每个接口都显式写了 operationId</h3>
 * springdoc 默认用 <b>Java 方法名</b>作为 operationId，而契约生成器（orval）
 * 直接用它作为前端函数名。后果：
 * <ul>
 *   <li>生成出 {@code page()} / {@code create()} / {@code detail()} 这种脱离资源的无语义函数名</li>
 *   <li><b>更严重</b>：一旦另一个 Controller 也有同名方法（比如 UserController 也要
 *       {@code create}），orval 会生成<b>重复导出</b>，前端直接编译失败 ——
 *       而报错位置在生成产物里，与真正的根因（两个 Java 方法同名）隔了很远</li>
 * </ul>
 * 显式 {@code operationId} 把"接口的对外名字"变成开发者主动决策的结果，
 * 而不是 Java 方法名的副产品。这是契约优先的应有纪律，
 * 也是唯一能让生成客户端在项目变大后依然可用的做法。
 */
@Tag(name = "认证", description = "登录、当前用户信息、动态菜单与权限")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * 刷新令牌的 Cookie 名与作用路径。
     *
     * <h3>为什么刷新令牌放在 HttpOnly Cookie 而不是响应体</h3>
     * 刷新令牌等于"7 天有效的登录凭证"。若交给 JS（响应体 → sessionStorage），
     * 一次 XSS 就能把它偷走 —— 访问令牌 30 分钟就过期，攻击者拿到的只是残渣，
     * 而<b>刷新令牌是长期钥匙</b>，必须让 JS 读不到。
     * Cookie 的 {@code HttpOnly} 从浏览器层面屏蔽了脚本读取；
     * {@code SameSite=Strict} 同时消除了它带来的 CSRF 面
     * （跨站请求不会携带该 Cookie）。
     *
     * <p>Path 限定在 {@code /api/v1/auth}：只有登录/刷新/登出会携带它，
     * 业务接口一个字节都不会多传。
     */
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthAppService authAppService;
    private final CaptchaAppService captchaAppService;
    private final RegisterAppService registerAppService;

    @Operation(operationId = "getLoginCaptcha", summary = "获取登录图形验证码",
            description = "返回验证码 id 与 Base64 PNG 图片。验证码<b>一次性</b>："
                    + "无论校验成功与否，使用后立即失效，需重新获取。"
                    + "登录时把 id 与用户输入一起提交给 /login")
    /**
     * 按 IP 限流（60 秒 20 次）。
     *
     * <p>限流的不只是"防刷图"，更是<b>防止攻击者批量拉取验证码来喂识别服务</b>：
     * 样本越多，自动化识别的成功率越高。限流把"喂样本"的速率压到没有意义的水平。
     */
    @RateLimit(limit = 20, windowSeconds = 60, scope = RateLimit.Scope.IP)
    @GetMapping("/captcha")
    public R<CaptchaResponse> captcha() {
        var issued = captchaAppService.issue();
        return R.ok(new CaptchaResponse(
                issued.captchaId(), issued.imageBase64(), issued.expiresInSeconds()));
    }

    @Operation(operationId = "register",
            summary = "自助注册",
            description = "在请求头指定的租户下创建账号。<b>注册不等于登录</b>：不签发任何令牌，"
                    + "新账号也不分配任何角色（登录后菜单为空），需管理员在用户管理中授权后才可用。"
                    + "需通过图形验证码；同一 IP 有频率限制")
    /**
     * 注册防刷：按 IP 限流（60 秒 5 次），比登录更严。
     *
     * <p>注册没有"账号锁定"这类天然兜底 —— 刷号的成本必须靠限流与验证码抬起来。
     */
    @RateLimit(limit = 5, windowSeconds = 60, scope = RateLimit.Scope.IP)
    @PostMapping("/register")
    public R<Long> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(registerAppService.register(new RegisterCommand(
                request.username(),
                request.nickname(),
                request.password(),
                request.email(),
                request.captchaId(),
                request.captchaCode())));
    }

    @Operation(operationId = "login",
            summary = "登录", description = "校验图形验证码与账号密码并签发访问令牌。"
                    + "连续失败达阈值会锁定账号。刷新令牌通过 HttpOnly Cookie 下发，不在响应体中返回。"
                    + "rememberDays 决定免登录天数（1/7/30，白名单校验）；留空表示不记住")
    /** 登录防爆破：按 IP 限流（60 秒 10 次）。暴力破解的最低成本防线。 */
    @RateLimit(limit = 10, windowSeconds = 60, scope = RateLimit.Scope.IP)
    @PostMapping("/login")
    public R<LoginResult> login(@Valid @RequestBody LoginRequest request,
                                HttpServletRequest servletRequest,
                                HttpServletResponse servletResponse) {
        LoginResult result = authAppService.login(new LoginCommand(
                request.tenantCode(),
                request.username(),
                request.password(),
                resolveClientIp(servletRequest),
                request.captchaId(),
                request.captchaCode(),
                request.rememberDays()));
        writeRefreshCookie(servletResponse, servletRequest, result);
        // ⚠️ 刷新令牌不进响应体：它已在 HttpOnly Cookie 里，
        // 若同时留在 body，XSS 依然读得到 —— 迁移就失去了意义
        return R.ok(withoutRefreshToken(result));
    }

    @Operation(operationId = "getCurrentUser", summary = "当前登录用户信息")
    @GetMapping("/me")
    public R<CurrentUserDTO> me() {
        return R.ok(authAppService.currentUserInfo());
    }

    @Operation(operationId = "getCurrentUserMenus",
            summary = "当前用户的菜单（扁平结构，前端建树）",
            description = "用于前端动态路由装配。返回 component 字段为字符串路径，前端用 import.meta.glob 映射")
    @GetMapping("/menus")
    public R<List<MenuDTO>> menus() {
        return R.ok(authAppService.currentUserMenus());
    }

    @Operation(operationId = "getCurrentUserPermissions",
            summary = "当前用户的权限码集合",
            description = "用于前端按钮级权限的初始渲染。服务端每次请求仍会独立鉴权，此结果不构成安全边界")
    @GetMapping("/permissions")
    public R<Set<String>> permissions() {
        return R.ok(authAppService.currentUserPermissions());
    }

    // ==================================================================
    // 会话治理：注销 / 刷新 / 在线会话
    // ==================================================================

    @Operation(operationId = "changeMyPassword", summary = "修改当前用户密码",
            description = "需提供原密码（防止会话被劫持后直接改密夺号）。修改成功后"
                    + "该用户的<b>全部会话与令牌立即失效</b>，包括当前设备 —— 需要重新登录。"
                    + "这是刻意的：改密码的语义就是把其它地方的登录一并作废")
    @PutMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authAppService.changeOwnPassword(request.oldPassword(), request.newPassword());
        return R.ok();
    }

    @Operation(operationId = "logout", summary = "退出登录",
            description = "注销当前会话并作废其刷新令牌，同时清除刷新令牌 Cookie。"
                    + "幂等：重复调用或会话已不存在时同样成功")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletResponse servletResponse) {
        authAppService.logout();
        clearRefreshCookie(servletResponse);
        return R.ok();
    }

    @Operation(operationId = "refreshToken", summary = "刷新令牌",
            description = "用刷新令牌换取新的访问/刷新令牌对（轮换语义：旧刷新令牌立即作废）。"
                    + "旧令牌被再次使用会触发重放保护 —— 整个令牌族被吊销、相关会话被强制下线。"
                    + "刷新令牌优先从 HttpOnly Cookie 读取；请求体形式保留给非浏览器客户端"
                    + "与滚动升级期间的旧版前端。新令牌同样经 Cookie 下发，不在响应体中返回。"
                    + "本端点是认证入口，在公开清单中，且不要求携带访问令牌")
    @PostMapping("/refresh")
    public R<LoginResult> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @Valid @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        // Cookie 优先；请求体兼容非浏览器客户端与升级期间的旧标签页。
        // 两者皆空时 service 会抛 REFRESH_TOKEN_INVALID —— 不必在此重复校验
        String token = cookieToken != null && !cookieToken.isBlank()
                ? cookieToken
                : (request != null ? request.refreshToken() : null);
        LoginResult result = authAppService.refresh(token);
        writeRefreshCookie(servletResponse, servletRequest, result);
        return R.ok(withoutRefreshToken(result));
    }

    // ==================================================================
    // 刷新令牌 Cookie
    // ==================================================================

    /**
     * 下发刷新令牌 Cookie。
     *
     * <h3>Max-Age 由"免登录天数"决定（服务端回读，不看请求参数）</h3>
     * <ul>
     *   <li>{@code rememberDays > 0}（用户明确勾选"记住 N 天"）→ 带 Max-Age，
     *       浏览器重启后仍持有刷新令牌</li>
     *   <li>{@code rememberDays == 0} → <b>不带</b> Max-Age（会话级 Cookie）：
     *       关闭浏览器即失效。这正是"不记住"应有的语义 ——
     *       如果这里图省事统一给个 Max-Age，用户没勾选却依然"被记住"，
     *       等于擅自延长了凭证的生命周期</li>
     * </ul>
     *
     * <p>登录与刷新<a>共用</a>本方法，保证两条路径下 Cookie 的属性永远一致 ——
     * 否则刷新（每次访问令牌过期都会发生）会悄悄改掉登录时的选择。
     */
    private void writeRefreshCookie(HttpServletResponse response,
                                    HttpServletRequest request, LoginResult result) {
        ResponseCookie.ResponseCookieBuilder builder =
                ResponseCookie.from(REFRESH_COOKIE, result.refreshToken())
                        .httpOnly(true)
                        .sameSite("Strict")
                        .path(REFRESH_COOKIE_PATH)
                        .secure(request.isSecure());
        if (result.rememberDays() > 0) {
            builder.maxAge(Duration.ofSeconds(result.refreshExpiresInSeconds()));
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    /** 清除刷新令牌 Cookie（登出）。 */
    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 剥掉刷新令牌后再序列化：Cookie 已携带它，body 里再出现一份等于白迁。 */
    private LoginResult withoutRefreshToken(LoginResult result) {
        return new LoginResult(result.accessToken(), result.expiresInSeconds(),
                null, result.refreshExpiresInSeconds(), result.user(),
                result.permissions(), result.roles(), result.rememberDays());
    }

    @Operation(operationId = "listMySessions", summary = "我的在线会话",
            description = "按最后活跃时间倒序。current=true 的那条是本次请求所在会话，"
                    + "注销它等价于退出登录")
    @GetMapping("/sessions")
    public R<List<SessionViewResponse>> sessions() {
        return R.ok(authAppService.listSessions().stream()
                .map(SessionViewResponse::from)
                .toList());
    }

    @Operation(operationId = "revokeMySession", summary = "注销我的某个会话",
            description = "用于\"把某台设备踢下线\"。传入当前会话等价于退出登录；"
                    + "传入不存在的会话 ID 静默成功（不泄露会话存在性）")
    @DeleteMapping("/sessions/{sessionId}")
    public R<Void> revokeSession(@PathVariable String sessionId) {
        authAppService.revokeSession(sessionId);
        return R.ok();
    }

    /**
     * 解析客户端真实 IP。
     *
     * <p>优先读 {@code X-Forwarded-For}（反向代理场景），取其中第一段 ——
     * 那是原始客户端 IP，后面的是各级代理。
     *
     * <p>⚠️ 该请求头可被客户端伪造，因此<b>只用于日志与审计展示</b>，
     * 绝不能用来做访问控制。若将来要用它做限流维度，
     * 必须先在网关注入并剥离用户传入的同名头。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            String trimmed = first.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 64) {
                return trimmed;
            }
        }
        return request.getRemoteAddr();
    }

    // ==================================================================
    // Wire 契约
    // ==================================================================

    @Schema(description = "修改密码请求")
    public record ChangePasswordRequest(

            @Schema(description = "原密码", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入原密码")
            String oldPassword,

            @Schema(description = "新密码（服务端按平台策略校验强度）",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入新密码")
            @Size(min = 6, max = 128, message = "新密码长度不合法")
            String newPassword
    ) {
    }

    @Schema(description = "自助注册请求")
    public record RegisterRequest(

            @Schema(description = "登录账号（租户内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入账号")
            @Size(max = 64, message = "账号长度不能超过 64")
            String username,

            @Schema(description = "昵称/姓名（留空则与账号同名）")
            @Size(max = 64, message = "昵称长度不能超过 64")
            String nickname,

            @Schema(description = "密码（服务端按平台策略校验强度）", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入密码")
            @Size(min = 6, max = 128, message = "密码长度不合法")
            String password,

            @Schema(description = "邮箱（可选）")
            @Size(max = 128, message = "邮箱长度非法")
            String email,

            @Schema(description = "图形验证码 id", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请先获取验证码")
            String captchaId,

            @Schema(description = "用户输入的验证码（大小写不敏感）", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入验证码")
            String captchaCode
    ) {
    }

    @Schema(description = "图形验证码")
    public record CaptchaResponse(

            @Schema(description = "验证码 id，登录时随 captchaId 提交", requiredMode = Schema.RequiredMode.REQUIRED)
            String captchaId,

            @Schema(description = "PNG 图片的 Base64（不含 data: 前缀），前端拼成 "
                    + "data:image/png;base64,{...} 即可显示", requiredMode = Schema.RequiredMode.REQUIRED)
            String imageBase64,

            @Schema(description = "有效期（秒），过期需重新获取", example = "180")
            long expiresInSeconds
    ) {
    }

    @Schema(description = "刷新令牌请求")
    public record RefreshTokenRequest(
            @Schema(description = "登录或上次轮换返回的刷新令牌",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请提供刷新令牌")
            String refreshToken
    ) {
    }
}
