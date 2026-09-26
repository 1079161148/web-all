package com.webadmin.interfaces.rest.interceptor;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 前端单页应用（SPA）的服务端托管。
 *
 * <h3>为什么后端要托管前端</h3>
 * 免费托管（Render 免费层只给一个服务）下，把前端 {@code dist} 与后端 jar
 * 放进同一个容器、由 Spring Boot 直接吐静态文件，前端访客与
 * {@code /api/**} 走同一个域名 —— 免去独立前端托管的 CORS/域名成本。
 * 同机正式部署（nginx 方案）时本配置同样无害：nginx 先接管静态与
 * 反代，请求到不了这里。
 *
 * <h3>SPA fallback 的语义</h3>
 * Vue Router 用 history 模式：直接访问 {@code /showcase/media-lab} 这类
 * 深链接时，服务器上并不存在该路径的文件 —— 必须回退到 {@code index.html}
 * 让前端路由接管。规则：
 * <ul>
 *   <li>请求路径在 classpath:/static 下有真实文件 → 原样返回；</li>
 *   <li>否则 → 返回 index.html（前端路由决定渲染什么，含它的 404 页）；</li>
 *   <li><b>例外</b>：{@code api/**} 永远不回退 —— 未匹配的 API 请求
 *       应得到 404 而不是一段 HTML（否则前端 fetch 拿到 200 + HTML，
 *       错误被吞掉，排查地狱）。</li>
 * </ul>
 *
 * <h3>优先级</h3>
 * {@code @RestController} 的精确映射优先于资源处理器，因此
 * {@code /api/v1/**} 的接口不受影响；资源链只兜住「没有任何控制器
 * 匹配」的 GET。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    /**
     * 静态资源根目录。默认 classpath:/static（jar 内）；
     * 容器部署时通过 {@code app.web.static-location=file:/app/static/}
     * 指向挂载进容器的前端产物目录。
     */
    @Value("${app.web.static-location:classpath:/static/}")
    private String staticLocation;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(staticLocation)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // API 未匹配 → 如实 404，绝不回退成 HTML
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        // 其余路径回退到 SPA 入口（前端路由接管，含它的 404 视图）
                        return location.createRelative("index.html");
                    }
                });
    }
}
