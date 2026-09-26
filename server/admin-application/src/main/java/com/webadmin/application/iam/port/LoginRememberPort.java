package com.webadmin.application.iam.port;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * "免登录天数"会话标记端口。
 *
 * <h3>要解决什么问题</h3>
 * 登录时用户选了"7 天内免登录"，服务端据此把刷新令牌 Cookie 设为 7 天有效期
 * （Max-Age）。但那之后每次<b>静默刷新</b>都要重新下发 Cookie —— 如果刷新时
 * 不知道该会话当初选的是几天，就只有两个坏选择：
 * <ul>
 *   <li>不设 Max-Age → Cookie 降级为会话级，用户关掉浏览器就"记不住了"（需求被破坏）</li>
 *   <li>固定设某天 → "不记住"的用户被悄悄延长成"记住"（安全语义被破坏）</li>
 * </ul>
 * 因此"记住几天"必须由服务端记住。
 *
 * <h3>为什么用独立 key 而不是扩建会话记录</h3>
 * 会话与刷新令牌的记录是<b>竖线分隔的定长结构</b>，其反序列化对段数敏感
 * （段数不符即视为"版本不匹配 → 已吊销"）。往中间插字段会让<b>发布瞬间
 * 所有在线用户被强制登出</b>。这个标记是"锦上添花"的信息，不值得用
 * 全站登出当代价 —— 独立 key 零兼容成本，且语义自洽：
 * 标记丢了（过期/Redis 清空）的后果只是"刷新后 Cookie 变会话级"，
 * 用户可以重新登录一次，不涉及任何安全降级。
 */
public interface LoginRememberPort {

    /**
     * 记录该会话的免登录天数。
     *
     * @param days 免登录天数（白名单值）；调用方保证 &gt; 0
     * @param ttl  标记有效期，与会话 TTL 对齐（标记不该比会话活得更久）
     */
    void mark(long tenantId, String sessionId, int days, Duration ttl);

    /**
     * 读回该会话的免登录天数。
     *
     * @return 空 = 未记录（不记住，或标记已过期）→ 调用方按"会话级 Cookie"处理
     */
    OptionalInt find(long tenantId, String sessionId);
}
