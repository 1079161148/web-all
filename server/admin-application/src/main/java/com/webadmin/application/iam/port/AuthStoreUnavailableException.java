package com.webadmin.application.iam.port;

/**
 * 认证存储（Redis）不可用。
 *
 * <h3>为什么必须与"没查到"区分开</h3>
 * 在吊销相关的读取上，{"没查到"} 与 {"查不了"} 是<b>两种完全相反</b>的含义：
 * <ul>
 *   <li><b>没查到</b>（{@code Optional.empty()}）：会话确实不存在 → 令牌已注销 → <b>拒绝</b></li>
 *   <li><b>查不了</b>（本异常）：存储故障 → 无法判定 → 应当<b>降级到版本号校验</b>，
 *       而不是把全体在线用户判成"已注销"</li>
 * </ul>
 * 如果实现把 Redis 异常也吞成 {@code Optional.empty()}（很多"优雅降级"的写法都会这么干），
 * 那么一次 Redis 抖动就等于<b>全站瞬间登出</b> —— 而且日志里只留下"令牌已注销"这种
 * 与真实原因完全无关的记录，排查会极其痛苦。
 */
public class AuthStoreUnavailableException extends RuntimeException {

    public AuthStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
