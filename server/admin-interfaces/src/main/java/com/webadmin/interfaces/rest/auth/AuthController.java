package com.webadmin.interfaces.rest.auth;

import com.webadmin.application.iam.AuthAppService;
import com.webadmin.application.iam.command.LoginCommand;
import com.webadmin.application.iam.dto.CurrentUserDTO;
import com.webadmin.application.iam.dto.LoginResult;
import com.webadmin.application.iam.dto.MenuDTO;
import com.webadmin.common.api.R;
import com.webadmin.interfaces.rest.auth.request.LoginRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final AuthAppService authAppService;

    @Operation(operationId = "login",
            summary = "登录", description = "校验账号密码并签发访问令牌。连续失败达阈值会锁定账号")
    @PostMapping("/login")
    public R<LoginResult> login(@Valid @RequestBody LoginRequest request,
                                HttpServletRequest servletRequest) {
        LoginResult result = authAppService.login(new LoginCommand(
                request.tenantCode(),
                request.username(),
                request.password(),
                resolveClientIp(servletRequest)));
        return R.ok(result);
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
}
