package com.webadmin.infrastructure.async;

import com.webadmin.common.tenant.TenantContext;
import org.springframework.core.task.TaskDecorator;

/**
 * 租户上下文跨线程传递装饰器。
 *
 * <h3>解决什么问题</h3>
 * {@link TenantContext} 基于 {@code ThreadLocal}，<b>不会自动跨线程传递</b>。
 * 因此下面这类代码会出严重问题：
 *
 * <pre>{@code
 * // 请求线程：tenantId = 1001
 * asyncExecutor.submit(() -> {
 *     // 工作线程：ThreadLocal 里可能是 null，也可能是上一个请求残留的 1002
 *     // 结果：抛异常，或者更糟 —— 静默读到别的租户的数据
 * });
 * }</pre>
 *
 * <p>这是设计文档 §6.2 列出的<b>三个穿透场景之首</b>，也是多租户系统最常见的数据泄露事故。
 *
 * <h3>为什么不用 TransmittableThreadLocal（TTL）</h3>
 * TTL 需要额外依赖，并且要求显式包装线程池。而 Spring 提供了标准的
 * {@link TaskDecorator} 扩展点，用它可以覆盖所有基于
 * {@code ThreadPoolTaskExecutor} 的受管线程池（{@code @Async} / {@code @Scheduled}
 * 自定义执行器等），无需引入新依赖。
 *
 * <h3>⚠️ 接入方式（P0 骨架尚未自动装配）</h3>
 * 请在实际启用异步能力时把它挂到执行器上，例如：
 * <pre>{@code
 * @Bean
 * public Executor asyncExecutor() {
 *     ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 *     executor.setTaskDecorator(new TenantContextTaskDecorator());
 *     executor.initialize();
 *     return executor;
 * }
 * }</pre>
 * 未接入前，任何 {@code @Async} / 手动提交任务的代码路径都<b>不安全</b>。
 * 补充测试：设计文档 §13.3 必测清单第 2 项（多租户隔离）必须覆盖异步场景。
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 在「提交任务的线程」上捕获上下文 —— 此时还是请求线程，值是正确的
        Long capturedTenantId = TenantContext.get().orElse(null);

        return () -> {
            Long previous = TenantContext.get().orElse(null);
            try {
                if (capturedTenantId == null) {
                    // 提交方没有租户上下文（如平台级任务），显式清空，
                    // 避免线程池复用时读到上一个请求的残留值
                    TenantContext.clear();
                } else {
                    TenantContext.set(capturedTenantId);
                }
                runnable.run();
            } finally {
                // 必须恢复现场，否则线程归还池后带着错误的租户上下文
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previous);
                }
            }
        };
    }
}
