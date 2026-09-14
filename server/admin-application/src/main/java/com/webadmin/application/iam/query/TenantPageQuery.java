package com.webadmin.application.iam.query;

import com.webadmin.common.api.PageQuery;
import com.webadmin.domain.iam.model.tenant.TenantStatus;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户分页查询条件。
 *
 * <p>继承 {@link PageQuery} 获得分页与归一化能力（含单页上限保护）。
 * 所有字段均为可选，供动态 SQL 拼接使用。
 */
@Getter
@Setter
public class TenantPageQuery extends PageQuery {

    /** 编码模糊匹配。 */
    private String code;

    /** 名称模糊匹配。 */
    private String name;

    /** 状态精确匹配。 */
    private TenantStatus status;

    /** 套餐编码精确匹配。 */
    private String planCode;

    /** 创建时间下界（含）。 */
    private Instant createdFrom;

    /** 创建时间上界（含）。 */
    private Instant createdTo;

    /**
     * 是否包含平台级租户（{@code tenant_id = 0}）。
     *
     * <p>租户列表本身属于平台级功能，需要跨租户查询，
     * 因此 MyBatis 租户拦截器对该场景必须显式放行（见设计文档 §6.3）。
     */
    private boolean includePlatform = true;
}
