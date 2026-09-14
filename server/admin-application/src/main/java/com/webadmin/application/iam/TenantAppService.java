package com.webadmin.application.iam;

import com.webadmin.application.iam.command.CreateTenantCommand;
import com.webadmin.application.iam.command.RenewTenantCommand;
import com.webadmin.application.iam.dto.TenantDTO;
import com.webadmin.application.iam.port.SubscriptionPlanRegistry;
import com.webadmin.application.iam.port.TenantQueryPort;
import com.webadmin.application.iam.query.TenantPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.tenant.SubscriptionPlan;
import com.webadmin.domain.iam.model.tenant.Tenant;
import com.webadmin.domain.iam.model.tenant.TenantCode;
import com.webadmin.domain.iam.model.tenant.TenantIdGenerator;
import com.webadmin.domain.iam.model.tenant.TenantName;
import com.webadmin.domain.iam.repository.TenantRepository;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户应用服务。
 *
 * <h3>职责边界（设计文档 §4.6）</h3>
 * 本类只做<b>编排</b>：加载聚合 → 调用领域方法 → 保存 → 发布事件。
 *
 * <p><b>禁止在本类写业务分支 {@code if}</b>。任何「什么情况下允许/不允许」的判断
 * 都应下沉到聚合根、值对象或领域服务，否则规则会重新散落到应用层，
 * 之前的 DDD 建模就白做了。评审时凡见到业务 if 一律打回。
 *
 * <h3>事务边界</h3>
 * 应用层是<b>唯一</b>的事务边界。Controller 与 Repository 都不得开启事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAppService {

    private final TenantRepository tenantRepository;
    private final TenantQueryPort tenantQueryPort;
    private final TenantIdGenerator tenantIdGenerator;
    private final SubscriptionPlanRegistry planRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ------------------------------------------------------------------
    // 写侧
    // ------------------------------------------------------------------

    /**
     * 创建租户。
     *
     * <p>流程：唯一性校验 → 构造聚合 → 持久化 → 发布领域事件。
     */
    @Transactional(rollbackFor = Exception.class)
    public TenantId createTenant(CreateTenantCommand command) {
        TenantCode code = TenantCode.of(command.code());
        TenantName name = TenantName.of(command.name());

        // 跨聚合的唯一性规则 —— 只能在应用层通过仓储校验
        if (tenantRepository.existsByCode(code)) {
            throw new BizException(IamErrorCode.TENANT_CODE_DUPLICATED,
                    "租户编码已存在: " + code.value());
        }

        SubscriptionPlan plan = planRegistry.requireByCode(command.planCode());

        Tenant tenant = Tenant.create(tenantIdGenerator.nextId(), code, name, plan, clock);
        tenantRepository.save(tenant);

        // 事件在持久化之后发布；Modulith 会将其包装为事务性 Outbox，
        // 保证只有事务提交成功才会真正投递（设计文档 §4.7）
        tenant.pullDomainEvents().forEach(eventPublisher::publishEvent);

        log.info("租户创建成功: id={}, code={}, plan={}", tenant.id(), code.value(), plan.code());
        return tenant.id();
    }

    /** 激活租户：PENDING / SUSPENDED / EXPIRED → ACTIVE。 */
    @Transactional(rollbackFor = Exception.class)
    public void activate(TenantId tenantId) {
        Tenant tenant = loadOrThrow(tenantId);
        tenant.activate(clock);
        saveAndPublish(tenant);
    }

    /** 暂停租户：数据保留但拒绝访问。 */
    @Transactional(rollbackFor = Exception.class)
    public void suspend(TenantId tenantId, String reason) {
        Tenant tenant = loadOrThrow(tenantId);
        tenant.suspend(reason, clock);
        saveAndPublish(tenant);
    }

    /** 续期：延长到期时间、切换套餐、重置配额；若已过期则自动恢复为 ACTIVE。 */
    @Transactional(rollbackFor = Exception.class)
    public void renew(RenewTenantCommand command) {
        Tenant tenant = loadOrThrow(command.tenantId());

        // 未指定套餐时延续当前套餐 —— 这是编排逻辑，不是业务规则，放在应用层是合适的
        SubscriptionPlan plan = (command.planCode() == null || command.planCode().isBlank())
                ? tenant.plan()
                : planRegistry.requireByCode(command.planCode());

        tenant.renew(command.months(), plan, clock);
        saveAndPublish(tenant);
    }

    /** 关闭租户（终态，不可恢复）。 */
    @Transactional(rollbackFor = Exception.class)
    public void close(TenantId tenantId, String reason) {
        Tenant tenant = loadOrThrow(tenantId);
        tenant.close(reason, clock);
        saveAndPublish(tenant);
    }

    /** 重命名租户。 */
    @Transactional(rollbackFor = Exception.class)
    public void rename(TenantId tenantId, String newName) {
        Tenant tenant = loadOrThrow(tenantId);
        tenant.rename(TenantName.of(newName), clock);
        saveAndPublish(tenant);
    }

    // ------------------------------------------------------------------
    // 读侧（CQRS：不经领域层，直查 VO）
    // ------------------------------------------------------------------

    public PageResult<TenantDTO> page(TenantPageQuery query) {
        query.normalize();
        return tenantQueryPort.page(query);
    }

    public TenantDTO getById(TenantId tenantId) {
        return tenantQueryPort.findById(tenantId)
                .orElseThrow(() -> new BizException(CommonErrorCode.RECORD_NOT_FOUND, "租户不存在"));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Tenant loadOrThrow(TenantId tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(IamErrorCode.TENANT_NOT_FOUND,
                        "租户不存在: " + tenantId.value()));
    }

    private void saveAndPublish(Tenant tenant) {
        tenantRepository.save(tenant);
        tenant.pullDomainEvents().forEach(eventPublisher::publishEvent);
    }
}
