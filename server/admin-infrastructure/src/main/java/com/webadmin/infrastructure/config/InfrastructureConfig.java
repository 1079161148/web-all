package com.webadmin.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础设施通用 Bean。
 */
@Configuration
public class InfrastructureConfig {

    /**
     * 统一时钟。
     *
     * <p>领域层所有的时间获取都必须通过注入的 {@link Clock}，禁止直接调用
     * {@code Instant.now()} —— 否则聚合根的时间相关逻辑（到期判断、状态流转）
     * 无法在测试中确定性验证。
     *
     * <p>生产使用 UTC 时区；单元测试通过替换为 {@code Clock.fixed(...)} 断言时间语义。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
