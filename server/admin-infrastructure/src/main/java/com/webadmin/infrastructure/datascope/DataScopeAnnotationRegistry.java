package com.webadmin.infrastructure.datascope;

import com.webadmin.common.datascope.DataScope;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link DataScope} 注解的解析与缓存。
 *
 * <h3>为什么需要缓存</h3>
 * MyBatis 传给数据权限处理器的是 {@code mappedStatementId}（形如
 * {@code com.webadmin.infrastructure.persistence.mapper.UserMapper.selectUserPage}），
 * 是一个<b>字符串</b>。要拿到注解必须反射定位方法，而这是在<b>每条 SQL</b> 上都会执行的操作。
 *
 * <p>不缓存的话，每一次查询都要做一次 {@code Class.forName} + 方法遍历 ——
 * 在虚拟线程 + 高并发的模型下会变成一个可观的开销，
 * 而且它对每个请求都是完全重复的工作。
 *
 * <h3>缓存的键为什么是 mappedStatementId 而不是 Method</h3>
 * 用字符串做键可以省掉 {@code Method} 对象的强引用 ——
 * 后者会阻止 Mapper 接口被卸载（在热部署 / 类加载器重载场景下造成泄漏）。
 * 缓存的值是注解本身（不可变的单例），不持有类引用。
 */
@Slf4j
@Component
public class DataScopeAnnotationRegistry {

    private final Map<String, Optional<DataScope>> cache = new ConcurrentHashMap<>();

    /**
     * 查找指定 Mapper 方法上的 {@link DataScope} 注解。
     *
     * <p>返回空表示"该查询不做数据权限过滤"，这是<b>绝大多数查询的常态</b>。
     */
    public Optional<DataScope> find(String mappedStatementId) {
        if (mappedStatementId == null || mappedStatementId.isBlank()) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(mappedStatementId, this::resolve);
    }

    private Optional<DataScope> resolve(String mappedStatementId) {
        int lastDot = mappedStatementId.lastIndexOf('.');
        if (lastDot <= 0) {
            return Optional.empty();
        }
        String className = mappedStatementId.substring(0, lastDot);
        String methodName = mappedStatementId.substring(lastDot + 1);

        try {
            Class<?> mapperType = Class.forName(className);
            for (Method method : mapperType.getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                DataScope annotation = method.getAnnotation(DataScope.class);
                if (annotation != null) {
                    return Optional.of(annotation);
                }
            }
        } catch (ClassNotFoundException ex) {
            // 不是我们的 Mapper（可能是框架内部语句）。静默忽略是安全的：
            // 找不到注解 = 不加数据权限条件，而租户隔离依然由
            // TenantLineInnerInterceptor 独立保证，不会因此跨租户泄露。
            log.debug("无法定位 Mapper 类（按无数据权限处理）：{}", className);
        }
        return Optional.empty();
    }

    /** 清空缓存（仅供测试使用）。 */
    void clearCache() {
        cache.clear();
    }
}
