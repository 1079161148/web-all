package com.webadmin.application.iam;

import com.webadmin.application.iam.port.CaptchaPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图形验证码应用服务。
 *
 * <h3>为什么要有这一层（而不是让 Controller 直接用端口）</h3>
 * 签发验证码不是"把端口包一层"的仪式：它是<b>登录流程的一部分</b>，
 * 将来会在这里长出策略（例如"同一 IP 连续失败 3 次才要求验证码"这类
 * 自适应挑战）。把调用点固定在应用层，策略变化就不必碰接口层。
 *
 * <h3>这里刻意不做的事</h3>
 * <ul>
 *   <li><b>不记录答案</b>：答案只活在存储与图片里，任何日志都不落
 *       （日志常被集中收集，写入等于把验证码送给了有日志读权限的人）</li>
 *   <li><b>不缓存 id</b>：验证码天然一次性，服务端无需维护"已签发列表"</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaAppService {

    private final CaptchaPort captchaPort;

    /** 签发一个新验证码（答案仅存在于存储与图片中）。 */
    public CaptchaPort.Issued issue() {
        CaptchaPort.Issued issued = captchaPort.issue();
        // 只记事件不记内容：审计能看到"签发了验证码"，但看不到是什么
        log.debug("已签发图形验证码 id={} 有效期={}s", issued.captchaId(), issued.expiresInSeconds());
        return issued;
    }
}
