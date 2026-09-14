package com.webadmin.common.api;

import java.util.List;

/**
 * 统一分页结果。
 *
 * <p>这是前后端分页契约的<b>唯一形态</b>：后端所有分页接口返回本类型，
 * 前端 {@code ProTable} 的 {@code request} 与之一一对应（设计文档 §11.4）。
 *
 * @param records 当前页数据
 * @param total   总记录数（必须与 records 使用<b>完全相同的过滤条件</b>，否则分页页数会错乱）
 * @param page    当前页码，从 1 开始
 * @param size    每页条数
 */
public record PageResult<T>(List<T> records, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return new PageResult<>(records, total, page, size);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    /**
     * 把每一条记录映射为另一种类型，分页元信息保持不变。
     *
     * <h3>为什么值得提供这个方法</h3>
     * 应用层返回的是<b>读模型</b>（{@code UserDTO}），而对外暴露的是
     * <b>契约类型</b>（{@code UserResponse}）—— 后者带 springdoc 注解，
     * 属于 interfaces 层。两者之间需要一次转换。
     *
     * <p>若每个模块都手写 {@code new PageResult<>(records.stream().map(...).toList(), total, page, size)}，
     * 就有 4 个字段需要在 7 个模块里各抄一遍 ——
     * <b>而其中 {@code total} 抄错一次，分页就会静默错乱</b>（页数对不上，但不报错）。
     * 收敛到一处后，这段逻辑只需要被测试一次。
     */
    public <R> PageResult<R> map(java.util.function.Function<? super T, ? extends R> mapper) {
        // 刻意不写成一行三元表达式：
        //   records == null ? List.of() : records.stream().map(mapper).toList()
        // 会因 List.of() 推导为 List<Object>、而另一支是 List<? extends R> 而无法统一，
        // 编译报"无法推断类型参数"。
        // 而且 mapper 的 `? super T` / `? extends R` 是通配符，
        // stream().map() 的结果是 List<? extends R>，赋给 List<R> 需要显式类型见证 <R>map(...)。
        List<R> mapped = (records == null || records.isEmpty())
                ? List.of()
                : records.stream().<R>map(mapper).toList();
        return new PageResult<>(mapped, total, page, size);
    }

    /** 总页数。 */
    public long totalPages() {
        return size <= 0 ? 0 : (total + size - 1) / size;
    }
}
