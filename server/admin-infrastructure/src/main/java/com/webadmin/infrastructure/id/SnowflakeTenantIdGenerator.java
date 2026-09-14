package com.webadmin.infrastructure.id;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.webadmin.domain.iam.model.tenant.TenantIdGenerator;
import com.webadmin.domain.shared.TenantId;
import org.springframework.stereotype.Component;

/**
 * 雪花算法租户 ID 生成器（{@link TenantIdGenerator} 端口的实现）。
 *
 * <p>直接复用 MyBatis-Plus 内置的 {@link IdWorker}，避免自己实现雪花算法 ——
 * 后者涉及时钟回拨处理、workerId 分配、序列号溢出等容易踩坑的细节。
 * 这正是设计文档「法则三：社区有就不自研」的体现。
 *
 * <p><b>为什么不用数据库自增主键</b>：多租户系统需要全局唯一且趋势递增的 ID
 * （便于分库分表、便于跨库关联），且自增 ID 会泄露租户数量等业务信息。
 */
@Component
public class SnowflakeTenantIdGenerator implements TenantIdGenerator {

    @Override
    public TenantId nextId() {
        return TenantId.of(IdWorker.getId());
    }
}
