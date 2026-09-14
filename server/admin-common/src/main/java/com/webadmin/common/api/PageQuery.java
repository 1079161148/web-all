package com.webadmin.common.api;

import lombok.Getter;
import lombok.Setter;

/**
 * 分页查询基类。
 *
 * <p>约束（设计文档 §9.6）：
 * <ul>
 *   <li><b>分页必填</b> —— 禁止无 {@code LIMIT} 的全表查询进入生产</li>
 *   <li>单页上限 {@value #MAX_SIZE}，防止前端传 {@code size=100000} 拖垮数据库</li>
 * </ul>
 *
 * <p>业务查询对象继承本类即可，例如 {@code TenantPageQuery extends PageQuery}。
 */
@Getter
@Setter
public class PageQuery {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    /** 页码，从 1 开始。 */
    private Integer page = DEFAULT_PAGE;

    /** 每页条数。 */
    private Integer size = DEFAULT_SIZE;

    /** 排序字段（白名单校验，禁止直接拼接进 SQL）。 */
    private String sortField;

    /** 排序方向：asc / desc。 */
    private String sortOrder = "desc";

    /**
     * 归一化分页参数。
     *
     * <p>在 AppService / QueryService 入口调用一次，后续不必再判空。
     */
    public void normalize() {
        if (page == null || page < 1) {
            page = DEFAULT_PAGE;
        }
        if (size == null || size < 1) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
        if (!"asc".equalsIgnoreCase(sortOrder) && !"desc".equalsIgnoreCase(sortOrder)) {
            sortOrder = "desc";
        }
    }

    /** 安全偏移量（供游标分页之外的场景使用；大表深分页请改用游标分页）。 */
    public int offset() {
        return (page - 1) * size;
    }
}
