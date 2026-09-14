package com.webadmin.domain.iam.repository;

import com.webadmin.domain.iam.model.tenant.Tenant;
import com.webadmin.domain.iam.model.tenant.TenantCode;
import com.webadmin.domain.shared.TenantId;
import java.util.Optional;

/**
 * 租户仓储接口（写侧）。
 *
 * <p>设计约束（设计文档 §4.8）：
 * <ul>
 *   <li><b>接口定义在领域层，实现在 infrastructure 层</b> —— 依赖倒置</li>
 *   <li>方法命名使用<b>领域语言</b>（{@code findByCode} / {@code save}），
 *       不暴露 SQL 概念（禁止 {@code selectOne} / {@code insertOrUpdate}）</li>
 *   <li>返回<b>聚合根</b>，不返回 PO。PO 是基础设施层的实现细节</li>
 *   <li>只负责<b>写侧</b>。读侧（分页列表、统计）走应用层的 QueryPort，
 *       直接查询到 VO，绕过领域层以换取性能（CQRS）</li>
 * </ul>
 */
public interface TenantRepository {

    /** 按 ID 加载聚合。 */
    Optional<Tenant> findById(TenantId id);

    /** 按编码加载聚合。 */
    Optional<Tenant> findByCode(TenantCode code);

    /**
     * 判断编码是否已被占用。
     *
     * <p>用于 {@code createTenant} 的前置校验 —— 这是<b>跨聚合的唯一性规则</b>，
     * 无法在单个聚合内保证，因此由应用层通过仓储查询。
     *
     * <p>注意：这只是「友好提示」，真正的唯一性由数据库唯一索引
     * {@code uk_tenant_code} 兜底（并发下两次查询都可能通过）。
     */
    boolean existsByCode(TenantCode code);

    /**
     * 保存聚合（新增或更新由实现按 ID 是否存在判定）。
     *
     * <p>实现必须处理乐观锁冲突：{@code version} 不匹配时<b>抛异常而非静默覆盖</b>。
     */
    void save(Tenant tenant);
}
