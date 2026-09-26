package com.webadmin.application.iam.security;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 数据权限模拟上下文（线程级）。
 *
 * <h3>它解决什么</h3>
 * 数据权限条件由 MyBatis 拦截器在 SQL 层施加，而拦截器读的是
 * {@code CurrentUserPort} + {@code PermissionResolver}。
 * 若为"预览"另写一套查询条件，那套条件与实际执行的条件<b>必然迟早漂移</b> ——
 * 于是"预览说你能看 8 条，实际打开看 12 条"，而这个功能存在的唯一意义
 * 就是让人相信预览结果。
 *
 * <p>所以这里的做法是：<b>临时替换拦截器读到的"主体"</b>，
 * 然后调用<b>真实的列表/统计查询</b>。条件由同一条代码路径产生，
 * 预览与实际在结构上不可能不一致。
 *
 * <h3>⚠️ 泄漏风险与为什么这里不可能泄漏</h3>
 * {@code ThreadLocal} 若在请求结束后未清理，线程池里的下一个请求会<b>继续以被模拟身份运行</b> ——
 * 这是权限系统最严重的一类事故。
 *
 * <p>本类的设计把这种可能消掉：模拟上下文<b>只能</b>通过 {@link #runWith} 在一段
 * 同步代码块内生效，进入即设置、退出即恢复（{@code finally} 保证）。
 * 它<b>不会</b>由某个 HTTP 请求设置后跨请求保留 ——
 * 也就是说，模拟不是"一个请求的状态"，而是"一次方法调用内的局部状态"。
 * 调用方（{@code RoleSimulationAppService}）在这个块内执行只读统计，
 * 块外再拼装返回值。
 *
 * <p>之所以不用 {@code RequestContextHolder} 的请求属性来实现，
 * 是因为那就要求模拟必须发生在 HTTP 请求线程内，
 * 而"将来可能出现由定时任务/内部任务触发的数据范围自检"这类场景会被堵死。
 *
 * <h3>嵌套语义</h3>
 * 进入时保存旧值、退出时<b>恢复</b>旧值（而不是直接清空），
 * 使嵌套调用（模拟内的代码再模拟）行为正确。
 */
public final class RoleSimulationContext {

    private static final ThreadLocal<SimulatedSubject> CURRENT = new ThreadLocal<>();

    private RoleSimulationContext() {
    }

    /** 当前是否处于模拟中，以及模拟的主体。 */
    public static Optional<SimulatedSubject> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 在模拟上下文中执行一段逻辑，退出时<b>必定</b>恢复原状态。
     *
     * <p>返回 {@link Supplier} 而不是 {@link Runnable}：模拟的主要用途是拿到
     * "可见条数"这类结果，返回值让调用处不必依赖局部变量收集。
     */
    public static <T> T runWith(SimulatedSubject subject, Supplier<T> action) {
        Objects.requireNonNull(subject, "模拟主体不能为空");
        Objects.requireNonNull(action, "模拟动作不能为空");

        SimulatedSubject previous = CURRENT.get();
        CURRENT.set(subject);
        try {
            return action.get();
        } finally {
            // 恢复到进入前的状态（而非无条件 remove），支持嵌套
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
