package com.webadmin.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 集成测试基类：启动真实的 MySQL 与 Redis 容器。
 *
 * <h3>为什么不用 H2（设计文档 §13.2 的硬性要求）</h3>
 * H2 与 MySQL 在<b>很多细节上行为不一致</b>，而项目恰恰依赖这些细节：
 * <ul>
 *   <li>{@code DATETIME(3)} 的毫秒精度与 ON UPDATE CURRENT_TIMESTAMP 行为</li>
 *   <li>{@code utf8mb4_general_ci} 的<b>大小写不敏感</b>比较 ——
 *       这与"用户名统一转小写"的设计相互印证，用 H2 测不出真实行为</li>
 *   <li>{@code JSON} 列类型、{@code ancestors LIKE 'prefix,%'} 的前缀索引利用</li>
 *   <li>{@code del_flag} 写成"本行 id"这类依赖 MySQL 表达式求值的写法</li>
 * </ul>
 * 换用内存库会得到一个"测了个假的"的绿灯 —— 那比没有测试更危险。
 *
 * <h3>单例容器的做法与理由</h3>
 * 容器在<b>静态块</b>中启动，而不是用 {@code @Container} 注解。
 * 原因：{@code @Container} 的生命周期跟随测试类，每个测试类都会重启一次容器
 * （几秒到几十秒）。设计文档 §13.5 明确要求"Testcontainers 单例容器，
 * 避免每个测试类重启"。
 *
 * <p>同一 JVM 内所有测试类共享同一组容器，由关闭钩子负责回收
 * （Testcontainers 的 Ryuk 侧车容器也会兜底，即使 JVM 被强杀）。
 *
 * <h3>为什么用 {@code @DynamicPropertySource} 而不是 {@code @ServiceConnection}</h3>
 * {@code @ServiceConnection} 需要把容器声明为 Spring Bean（通常在
 * {@code @TestConfiguration} 里），而那样就回到了"每个测试上下文启动一个容器"的模型。
 * {@code @DynamicPropertySource} 配合静态字段，才能让容器成为真正的进程级单例。
 */
public abstract class AbstractIntegrationTest {

    private static final String MYSQL_IMAGE = "mysql:8.4";
    private static final String REDIS_IMAGE = "redis:8-alpine";
    private static final int REDIS_PORT = 6379;

    private static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("webadmin_test")
            .withUsername("test")
            .withPassword("test")
            // 与服务端保持一致：迁移脚本里用了 utf8mb4 与 JSON 列，
            // 字符集不同会让"本地能跑、测试报错"这类问题变得难以理解
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_general_ci");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(REDIS_PORT);

    static {
        MYSQL.start();
        REDIS.start();
        // 显式回收。虽然 Ryuk 会兜底，但显式停止能让本地连续跑测试时
        // 更快释放端口与内存，也让"测试结束"在进程列表里可见。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            REDIS.stop();
            MYSQL.stop();
        }, "testcontainers-shutdown"));
    }

    /**
     * 把容器地址注入 Spring 配置。
     *
     * <p>只覆盖"连接信息"，不覆盖任何业务配置 ——
     * 业务配置（如 JWT 密钥、初始密码）应由各测试类按需通过
     * {@code @TestPropertySource} 或 {@code @DynamicPropertySource} 自己声明，
     * 这样"测试用的业务参数"在测试代码里是可见的，而不是藏在基类里。
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }
}
