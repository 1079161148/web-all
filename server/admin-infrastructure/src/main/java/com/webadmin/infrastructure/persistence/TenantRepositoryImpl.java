package com.webadmin.infrastructure.persistence;

import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.domain.iam.model.tenant.Tenant;
import com.webadmin.domain.iam.model.tenant.TenantCode;
import com.webadmin.domain.iam.repository.TenantRepository;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.infrastructure.persistence.converter.TenantConverter;
import com.webadmin.infrastructure.persistence.mapper.TenantMapper;
import com.webadmin.infrastructure.persistence.po.TenantPO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * 租户仓储实现。
 *
 * <p>本类是<b>依赖倒置的落点</b>：实现 {@link TenantRepository}（定义在领域层），
 * 因此领域层不需要知道 MyBatis-Plus 的存在（设计文档 §4.3）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>负责聚合 ↔ PO 的转换（通过 {@link TenantConverter}）</li>
 *   <li>负责乐观锁冲突的检测与抛出（<b>抛异常而非静默覆盖</b>）</li>
 *   <li><b>不负责</b>开事务 —— 事务边界在应用层的 AppService</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TenantRepositoryImpl implements TenantRepository {

    private final TenantMapper tenantMapper;
    private final TenantConverter tenantConverter;

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return Optional.ofNullable(tenantMapper.selectById(id.value()))
                .map(tenantConverter::toDomain);
    }

    @Override
    public Optional<Tenant> findByCode(TenantCode code) {
        return Optional.ofNullable(tenantMapper.selectByCode(code.value()))
                .map(tenantConverter::toDomain);
    }

    @Override
    public boolean existsByCode(TenantCode code) {
        return tenantMapper.selectByCode(code.value()) != null;
    }

    @Override
    public void save(Tenant tenant) {
        TenantPO existing = tenantMapper.selectById(tenant.id().value());

        if (existing == null) {
            TenantPO po = tenantConverter.toPO(tenant);
            tenantMapper.insert(po);
            return;
        }

        // 更新：把聚合的可变状态合并到已加载的 PO 上，保留 version 与审计字段。
        // 若直接 toPO() 新建对象会丢掉 version，乐观锁将完全失效（静默覆盖他人修改）。
        tenantConverter.mergeIntoPO(tenant, existing);

        int affected = tenantMapper.updateById(existing);
        if (affected == 0) {
            // version 不匹配或记录已被删除 —— 两者都必须让调用方感知，
            // 绝不能当作「更新成功」继续往下走
            log.warn("租户并发修改冲突: id={}", tenant.id().value());
            throw new BizException(CommonErrorCode.CONCURRENT_MODIFICATION,
                    "租户已被他人修改，请刷新后重试");
        }
    }
}
