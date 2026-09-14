package com.webadmin.infrastructure.persistence.query;

import com.webadmin.application.iam.dto.TenantDTO;
import com.webadmin.application.iam.port.TenantQueryPort;
import com.webadmin.application.iam.query.TenantPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.infrastructure.persistence.converter.TenantPoMapper;
import com.webadmin.infrastructure.persistence.mapper.TenantMapper;
import com.webadmin.infrastructure.persistence.po.TenantPO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 租户读侧查询实现（CQRS Query）。
 *
 * <p>与 {@code TenantRepositoryImpl} 的区别：
 * <ul>
 *   <li>读侧<b>不重建聚合根</b>，直接把 PO 映射为读模型，省掉值对象还原与校验开销</li>
 *   <li>读侧不受聚合边界约束，可以自由 JOIN / 聚合 / 走覆盖索引（设计文档 §4.6）</li>
 *   <li>写侧保证不变量，读侧保证性能 —— 这是 CQRS 的核心取舍</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class TenantQueryPortImpl implements TenantQueryPort {

    private final TenantMapper tenantMapper;
    private final TenantPoMapper tenantPoMapper;

    @Override
    public PageResult<TenantDTO> page(TenantPageQuery query) {
        List<TenantPO> records = tenantMapper.selectPageByQuery(query);
        long total = tenantMapper.countByQuery(query);
        return PageResult.of(
                tenantPoMapper.toDTOList(records),
                total,
                query.getPage(),
                query.getSize());
    }

    @Override
    public Optional<TenantDTO> findById(TenantId id) {
        return Optional.ofNullable(tenantMapper.selectById(id.value()))
                .map(tenantPoMapper::toDTO);
    }

    @Override
    public Optional<TenantDTO> findByCode(String code) {
        return Optional.ofNullable(tenantMapper.selectByCode(code))
                .map(tenantPoMapper::toDTO);
    }
}
