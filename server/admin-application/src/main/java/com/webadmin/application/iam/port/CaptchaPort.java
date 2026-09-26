package com.webadmin.application.iam.port;

/**
 * 图形验证码端口。
 *
 * <h3>为什么校验是"消费"而不是"读取"</h3>
 * 验证码是<b>一次性凭证</b>：校验必须"取出即删除"，否则同一个验证码可以在
 * 有效期内被重复使用 —— 那等于没有验证码（攻击者拿到一次正确值就能无限重放）。
 * 因此端口只提供 {@link #verifyAndConsume} 这一个校验入口，<b>刻意不提供</b>
 * "只读比对"的方法：让"读一下再决定"这种用法在编译期就不可能写出来。
 *
 * <h3>故障语义：fail-closed</h3>
 * 存储不可用时返回 {@code false}（拒绝登录）而不是放行。验证码的作用是
 * 挡住自动化爆破，Redis 抖动时"乐观放行"会让防线在最需要它的时候消失；
 * 而拒绝的代价只是用户稍后重试登录。
 */
public interface CaptchaPort {

    /**
     * 生成并存储一个验证码。
     *
     * @return 验证码 id 与可直接用于 {@code <img src>} 的 Base64 PNG；
     *         答案本身<b>不出现在返回值里</b>，只存在于服务端存储中
     */
    Issued issue();

    /**
     * 校验并<b>原子消费</b>：无论成功与否，该验证码立即失效。
     *
     * @param captchaId 验证码 id；null/空一律拒绝
     * @param input     用户输入；大小写不敏感、忽略首尾空白
     * @return 校验是否通过
     */
    boolean verifyAndConsume(String captchaId, String input);

    /** 验证码有效期（秒），用于下发给前端展示"剩余时间"与测试断言。 */
    long expiresInSeconds();

    /** 一次签发的产物。 */
    record Issued(String captchaId, String imageBase64, long expiresInSeconds) {
    }
}
