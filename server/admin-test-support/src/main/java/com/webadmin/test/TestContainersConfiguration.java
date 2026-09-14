package com.webadmin.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试基础设施：Testcontainers 单例容器。
 *
 * <h3>为什么必须用真实 MySQL 而不是 H2</h3>
 * 设计文档 §13.2 明确禁用 H2：MySQL 与 H2 在
 * <b>分区表、JSON 函数、ON UPDATE CURRENT_TIMESTAMP、索引行为、大小写敏感性</b>
 * 上存在差异，用 H2 测出来的结果是「测了个假的」—— 本地全绿，上线报错。
 *
 * <h3>为什么定义成 static 单例</h3>
 * 容器启动一次、所有测试共享，避免每个测试类都重启 MySQL（那会让集成测试从秒级变成分钟级）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

    private static final String MYSQL_IMAGE = "mysql:8.4";

    /**
     * MySQL 容器。
     *
     * <p>{@link ServiceConnection} 会让 Spring Boot 自动把容器的连接信息
     * 注入到 {@code spring.datasource.*}，无需手写 {@code @DynamicPropertySource}。
     */
    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    public MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("webadmin_test")
                .withUsername("test")
                .withPassword("test")
                .withCommand(
                        // 与生产保持一致的字符集，避免 utf8mb4 相关行为差异
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_general_ci");
    }

    // TODO(P1)：接入认证与缓存后，在此追加 Redis 与 MinIO 容器：
    //   @Bean @ServiceConnection RedisContainer<?> redisContainer() { ... }
    //   @Bean GenericContainer<?> minioContainer() { ... }
}
