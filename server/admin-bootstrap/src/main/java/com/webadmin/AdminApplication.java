package com.webadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 中台系统启动类。
 *
 * <p>本模块是<b>唯一</b>包含 {@code main} 的模块，职责是「装配」——
 * 把 interfaces / application / infrastructure 各层组装成可运行的应用，
 * 并承载 Flyway 迁移脚本。
 *
 * <p>模块扫描根为 {@code com.webadmin}，因此各层的 {@code @Component} / {@code @Service} /
 * {@code @Repository} / {@code @Configuration} 都能被发现，无需逐层声明扫描路径。
 */
@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
