package com.webadmin.common.tenant;

import java.util.Optional;

/**
 * 租户上下文（请求级）。
 *
 * <p><b>这是多租户隔离的第一道防线</b>（设计文档 §6.2）。任何数据访问、缓存 Key、
 * 文件路径、异步任务都必须能拿到当前的 {@code tenantId}。
 *
 * <h3>⚠️ 三个必须显式处理的穿透场景</h3>
 * <table border="1">
 *   <tr><th>场景</th><th>问题</th><th>处理方式</th></tr>
 *   <tr>
 *     <td>异步线程池</td><td>{@link ThreadLocal} 不跨线程，会读到 null 或上一个租户的值（数据泄露）</td>
 *     <td>装配 {@code TenantContextTaskDecorator}（见 infrastructure 模块）</td>
 *   </tr>
 *   <tr>
 *     <td>定时任务</td><td>无请求上下文</td>
 *     <td>遍历租户执行，逐个 {@code set} / {@code finally clear}</td>
 *   </tr>
 *   <tr>
 *     <td>MQ / 事件监听</td><td>消费端无上下文</td>
 *     <td>消息体强制携带 tenantId，消费入口先 {@code set}</td>
 *   </tr>
 * </table>
 *
 * <h3>为什么用 ThreadLocal 而不是 ScopedValue</h3>
 * {@code ScopedValue} 需要结构化并发作用域，而 Web 请求的租户上下文需要跨 Filter / 拦截器 /
 * 异步回调存在，用 ScopedValue 会强制大范围重构。当前实现保留 {@link ThreadLocal}，
 * 但通过本门面类隔离，后续若要切换到 ScopedValue 只需改这一处（见设计文档 §6.2）。
 */
public final class TenantContext {

    /**
     * 平台级租户 ID。
     *
     * <p>用于平台级数据（字典默认值、系统配置），以及无租户上下文的系统任务。
     */
    public static final long PLATFORM_TENANT_ID = 0L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(long tenantId) {
        CURRENT.set(tenantId);
    }

    /** 可空读取：仅用于「允许无租户」的场景（如登录接口本身）。 */
    public static Optional<Long> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 强制读取：<b>业务代码应优先使用本方法</b>，缺失直接抛异常。
     *
     * <p>这是刻意的「防漏隔离」设计：宁可快速失败，也不要静默跨租户串数据。
     */
    public static long require() {
        Long tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "租户上下文缺失：该调用路径未设置 TenantContext。"
                            + "若为异步/定时/MQ 场景，请显式传递租户上下文（见设计文档 §6.2）");
        }
        return tenantId;
    }

    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    public static boolean isPlatform() {
        return Long.valueOf(PLATFORM_TENANT_ID).equals(CURRENT.get());
    }

    /**
     * 清理上下文。
     *
     * <p><b>必须在 {@code finally} 中调用</b>。线程池复用线程时，残留的租户上下文
     * 会导致下一个请求读到错误租户 —— 这是最典型的跨租户数据泄露事故。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在指定租户上下文中执行，执行完毕恢复原值（含异常路径）。
     *
     * <p>供定时任务遍历租户、MQ 消费、测试使用。
     */
    public static <T> T callWith(long tenantId, java.util.function.Supplier<T> action) {
        Long previous = CURRENT.get();
        try {
            CURRENT.set(tenantId);
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runWith(long tenantId, Runnable action) {
        callWith(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
