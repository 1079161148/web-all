package com.webadmin.application.iam.port;

import com.webadmin.application.iam.dto.TenantDTO;
import com.webadmin.application.iam.query.TenantPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.domain.shared.TenantId;
import java.util.Optional;

/**
 * 租户读取端口（读侧 / CQRS Query）。
 *
 * <p>设计要点（设计文档 §4.6）：
 * <ul>
 *   <li>读路径<b>直接查询到 VO</b>，不经过领域模型，避免聚合重建与对象转换开销</li>
 *   <li>接口定义在应用层（读模型的消费者在这里），实现在 infrastructure 层</li>
 *   <li>读侧可以自由使用 JOIN、聚合函数等 SQL 能力，不受聚合边界限制</li>
 * </ul>
 */
public interface TenantQueryPort {

    /** 分页查询租户列表。 */
    PageResult<TenantDTO> page(TenantPageQuery query);

    /** 按 ID 查询单个租户。 */
    Optional<TenantDTO> findById(TenantId id);

    /** 按编码查询单个租户。 */
    Optional<TenantDTO> findByCode(String code);
}
