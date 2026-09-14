package com.webadmin.infrastructure.id;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.webadmin.domain.shared.IdGenerator;
import org.springframework.stereotype.Component;

/**
 * 通用主键生成器（{@link IdGenerator} 端口的实现），复用 MyBatis-Plus 的 {@link IdWorker}。
 *
 * <p>与 {@code SnowflakeTenantIdGenerator} 的关系：后者是最早期为租户单独定义的，
 * 保留以维持已有代码稳定。新增聚合统一用本实现。
 * 两者底层都是同一个 {@code IdWorker}，因此不存在"两套 ID 生成器"的额外风险。
 */
@Component
public class SnowflakeIdGenerator implements IdGenerator {

    @Override
    public long nextId() {
        return IdWorker.getId();
    }
}
