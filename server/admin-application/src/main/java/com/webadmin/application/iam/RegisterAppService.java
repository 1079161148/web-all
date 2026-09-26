package com.webadmin.application.iam;

import com.webadmin.application.iam.command.CreateUserCommand;
import com.webadmin.application.iam.command.RegisterCommand;
import com.webadmin.application.iam.port.CaptchaPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.repository.TenantRepository;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自助注册应用服务。
 *
 * <h3>本功能的安全模型：注册 = 建一个"什么都不能做"的账号</h3>
 * 注册是系统里<b>唯一对匿名用户开放写入</b>的业务入口，因此它的设计原则是
 * "宁可让新用户多等一步，也不给注册流程任何权限"：
 * <ol>
 *   <li><b>不自动登录、不签发任何令牌</b>：注册成功后用户仍需正常登录，
 *       登录会走完整的验证码 + 失败锁定 + 会话治理链路</li>
 *   <li><b>不分配任何角色</b>：新账号登录后菜单为空、权限集为空。
 *       管理员在「用户管理」里授权后才真正可用 —— 这保证"注册"不会
 *       绕开既有的授权流程（对比：若默认给一个"普通用户"角色，
 *       就等于把权限授予权交给了任何能打开注册页的人）</li>
 *   <li><b>验证码 + IP 限流</b>（限流在 Controller）：注册没有"账号锁定"兜底，
 *       批量刷号是它最主要的滥用形态</li>
 *   <li><b>不返回任何账号存在性之外的信息</b>：响应只有用户 id 与用户名</li>
 * </ol>
 *
 * <h3>为什么复用 {@link UserAppService#createUser}</h3>
 * 密码强度、用户名格式与唯一性、租户配额、领域事件与审计 —— 这些规则
 * 在管理员建号时已经实现过一次。注册<b>另起一套校验</b>几乎必然会漂移
 * （典型症状：管理员建的号密码要 8 位，注册的号 6 位就能过）。
 * 复用的代价只是要显式收窄字段：角色、部门、性别一律不开放。
 *
 * <h3>租户从哪来</h3>
 * 与登录同源：请求头（{@code X-Tenant-Id}）确定的租户上下文。
 * 并且这里会<b>额外校验租户可用</b>——不能往已停用/已过期的租户里注册账号
 * （那会产生谁也管不了的僵尸数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterAppService {

    private final CaptchaPort captchaPort;
    private final UserAppService userAppService;
    private final TenantRepository tenantRepository;
    private final Clock clock;

    /**
     * 注册开关（默认开启）。
     *
     * <p>关闭后接口返回明确的业务错误（而不是 404）—— "注册已关闭"
     * 是可以公开的事实，让用户去猜"地址是不是写错了"没有意义。
     */
    @Value("${webadmin.security.register.enabled:true}")
    private boolean registerEnabled;

    @Value("${webadmin.security.captcha.enabled:true}")
    private boolean captchaEnabled;

    /**
     * 注册一个新账号。
     *
     * @return 新用户 id（<b>不签发令牌</b>：注册不等于登录）
     */
    @Transactional
    public long register(RegisterCommand command) {
        if (!registerEnabled) {
            throw new BizException(CommonErrorCode.REGISTER_DISABLED);
        }

        // 验证码在最前面：与登录同理，公开入口的第一道闸门越早越省资源。
        // 同时它天然限速了"用注册接口探测用户名是否存在"的尝试
        if (captchaEnabled
                && !captchaPort.verifyAndConsume(command.captchaId(), command.captchaCode())) {
            throw new BizException(CommonErrorCode.CAPTCHA_INVALID);
        }

        long tenantId = TenantContext.require();
        assertTenantAccessible(tenantId);

        String nickname = command.nickname() == null || command.nickname().isBlank()
                ? command.username()
                : command.nickname();

        // roleIds 传空集是**刻意的**，不是遗漏：见类注释第 2 条
        long userId = userAppService.createUser(new CreateUserCommand(
                command.username(),
                nickname,
                command.rawPassword(),
                command.email(),
                null,
                null,
                2,
                Set.of()));

        log.info("自助注册成功 tenantId={} userId={} username={}（未分配角色，等待管理员授权）",
                tenantId, userId, command.username());
        return userId;
    }

    /**
     * 断言租户存在且可用（与登录入口同一判定语义）。
     *
     * <p>用聚合的 {@code assertAccessible(clock)} 而不是"状态等于 ACTIVE 就算过"：
     * 它按<b>有效期实时判定</b>，因此即使"过期自动冻结"的定时任务还没跑，
     * 过期租户也注册不进来。
     */
    private void assertTenantAccessible(long tenantId) {
        tenantRepository.findById(TenantId.ofPersisted(tenantId))
                .orElseThrow(() -> new BizException(IamErrorCode.TENANT_NOT_FOUND, "租户不存在"))
                .assertAccessible(clock);
    }
}
