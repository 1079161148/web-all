package com.webadmin.auth;

import com.webadmin.application.iam.AuthAppService;
import com.webadmin.application.iam.CaptchaAppService;
import com.webadmin.application.iam.command.LoginCommand;
import com.webadmin.application.iam.dto.LoginResult;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.testsupport.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录安全加固（图形验证码 + 免登录天数）的集成测试。
 *
 * <h3>被验证的四条安全性质</h3>
 * <ol>
 *   <li><b>验证码不可绕过</b>：缺失或错误一律拒绝 —— 这是它唯一的存在理由</li>
 *   <li><b>验证码一次性</b>：校验后立即作废，<b>失败也算作废</b>。
 *       若"失败不消费"，攻击者就能拿一个 id 无限次试错，
 *       4 位字符（约 100 万组合）在并发下并非不可穷举</li>
 *   <li><b>免登录天数受白名单约束</b>：客户端传 365 必须被拒。
 *       这个字段直接换算成会话 TTL —— 采信客户端等于"改一个数字换一年会话"</li>
 *   <li><b>不记住 = 会话级</b>：rememberDays 为 0，控制器据此不下发 Max-Age</li>
 * </ol>
 *
 * <h3>为什么用反射开关，而不是 @TestPropertySource</h3>
 * {@code AbstractIntegrationTest} 通过 {@code @DynamicPropertySource} 关闭了
 * 验证码（否则所有 IT 都要"识图"）。而 {@code @DynamicPropertySource} 的
 * 优先级高于 {@code @TestPropertySource}/注解属性，子类再用注解打开**不会生效**
 * —— 那样测试会静默地测了个假命题（验证码根本没开）。
 * 因此这里直接翻转被测服务的开关字段：意图明确、结果确定，
 * 且开关本身就是生产代码里真实存在的分支。
 *
 * <h3>为什么能"知道答案"</h3>
 * 验证码答案存在 Redis（键规则见 {@code RedisCaptchaAdapter}）。
 * 测试直接注入答案而不是去识别图片 —— 前者验证的是<b>服务端语义</b>，
 * 后者验证的是"测试环境有没有 OCR"。图片是否可读由人工/浏览器验证。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "webadmin.security.init-admin.enabled=true",
                "webadmin.security.init-admin.password=Test@123456",
                "spring.main.banner-mode=off"
        })
class CaptchaLoginIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Test@123456";
    private static final String CAPTCHA_KEY_PREFIX = "webadmin:captcha:";

    @Autowired
    private AuthAppService authAppService;

    @Autowired
    private CaptchaAppService captchaAppService;

    @Autowired
    private StringRedisTemplate redis;

    /** 登录会经 MyBatis 查询，需要租户上下文（与真实请求路径一致）。 */
    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        // 本类专门验证"开启验证码"的语义
        ReflectionTestUtils.setField(authAppService, "captchaEnabled", true);
    }

    @AfterEach
    void tearDown() {
        // 还原开关：同一 Spring 上下文可能被其它测试类复用（容器共享）
        ReflectionTestUtils.setField(authAppService, "captchaEnabled", false);
        TenantContext.clear();
    }

    /** 签发验证码并直接写入已知答案，模拟"用户看图并正确输入"。 */
    private String issueCaptchaWithAnswer(String answer) {
        String captchaId = captchaAppService.issue().captchaId();
        redis.opsForValue().set(CAPTCHA_KEY_PREFIX + captchaId, answer, Duration.ofMinutes(3));
        return captchaId;
    }

    private LoginResult login(String captchaId, String captchaCode, Integer rememberDays) {
        return authAppService.login(new LoginCommand(
                null, ADMIN_USERNAME, ADMIN_PASSWORD, "127.0.0.1",
                captchaId, captchaCode, rememberDays));
    }

    @Test
    @DisplayName("缺少验证码：拒绝登录（验证码不可省略）")
    void rejectsMissingCaptcha() {
        assertThatThrownBy(() -> login(null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码");
    }

    @Test
    @DisplayName("验证码错误：拒绝登录，且该验证码已被消费（不能拿同一 id 继续试错）")
    void rejectsWrongCaptchaAndConsumesIt() {
        String captchaId = issueCaptchaWithAnswer("abcd");

        assertThatThrownBy(() -> login(captchaId, "zzzz", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码");

        // 再用**正确**答案重试同一个 id：必须仍然失败 —— 失败已消费掉它。
        // 这条断言是整个测试里最关键的一条：它把"一次性"钉死在"无论对错"上
        assertThatThrownBy(() -> login(captchaId, "abcd", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码");
    }

    @Test
    @DisplayName("验证码正确 + 免登录 7 天：登录成功，会话与刷新令牌有效期均为 7 天")
    void acceptsCorrectCaptchaAndAppliesRememberDays() {
        String captchaId = issueCaptchaWithAnswer("wxyz");

        LoginResult result = login(captchaId, "wxyz", 7);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.rememberDays()).isEqualTo(7);
        assertThat(result.refreshExpiresInSeconds()).isEqualTo(Duration.ofDays(7).toSeconds());
        // 访问令牌仍是短效的：免登录延长的是"换令牌的能力"，不是"业务访问权限"
        assertThat(result.expiresInSeconds()).isLessThan(Duration.ofHours(2).toSeconds());
    }

    @Test
    @DisplayName("免登录天数越权：365 天被白名单拒绝（不做静默纠正）")
    void rejectsRememberDaysOutsideWhitelist() {
        String captchaId = issueCaptchaWithAnswer("mnop");

        assertThatThrownBy(() -> login(captchaId, "mnop", 365))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("免登录天数");
    }

    @Test
    @DisplayName("不记住：rememberDays 为 0（控制器据此不下发 Cookie 有效期）")
    void noRememberDaysMeansSessionCookie() {
        String captchaId = issueCaptchaWithAnswer("qrst");

        LoginResult result = login(captchaId, "qrst", null);

        assertThat(result.rememberDays()).isZero();
    }
}
