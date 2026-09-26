package com.webadmin.infrastructure.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 消息推送的跨实例广播订阅。
 *
 * <p>每个实例都订阅同一频道：发布公告的实例把
 * {@code tenantId:userId} 发上去，<b>所有</b>实例（包括发布者自己）
 * 收到后把信号写给本地持有的 SSE 连接 ——
 * 用户连在哪台机器上无关紧要，这正是"无状态服务 + 共享广播"的用法。
 *
 * <p>频道里只有「租户与用户 ID」两个数字，不含消息内容：
 * 内容以数据库为准，推送只是刷新信号（见 {@code MessageAppService} 的说明）。
 * 这让频道的泄漏面与语义都保持最小。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MessageBroadcastConfig {

    @Bean
    public RedisMessageListenerContainer messageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageStreamRegistry registry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                String body = new String(message.getBody());
                int idx = body.indexOf(':');
                if (idx <= 0) {
                    return;
                }
                long tenantId = Long.parseLong(body.substring(0, idx));
                long userId = Long.parseLong(body.substring(idx + 1));
                // 信号本身不含数据：客户端收到后重新拉取未读数
                registry.deliver(tenantId, userId, "refresh");
            } catch (RuntimeException ex) {
                // 单条广播处理失败不应影响订阅线程的后续消息
                log.warn("处理消息广播失败：{}", ex.getMessage());
            }
        }, new ChannelTopic(MessageStreamRegistry.broadcastChannel()));
        return container;
    }
}
