package com.webadmin.application.iam.job;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务调度。
 *
 * <p>放在本模块而不是启动类：调度是应用层的能力（任务调用的是应用服务），
 * 启动类只做装配。<b>整个系统目前只有这一个调度入口</b>，
 * 新增定时任务时应到这里登记，便于一眼看清"系统里有哪些后台任务"。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
