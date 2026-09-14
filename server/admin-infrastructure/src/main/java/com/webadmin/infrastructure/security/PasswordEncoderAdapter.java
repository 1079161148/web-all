package com.webadmin.infrastructure.security;

import com.webadmin.domain.iam.model.user.PasswordHash;
import com.webadmin.domain.iam.port.PasswordEncoderPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码加密适配器：实现领域层端口，内部委托 Spring Security 的 {@link BCryptPasswordEncoder}。
 *
 * <h3>cost 因子为什么可配</h3>
 * BCrypt 的 cost 决定哈希耗时。安全要求随硬件提升而上调（今天的 10 相当于五年前的 8）。
 * 把它做成配置项，运维就能在换硬件后直接调高，而不用改代码重新发版。
 *
 * <p>默认 10：在普通服务器上单次约 50~100ms。这个数字是刻意的 ——
 * 登录本来就该慢一点（对攻击者是成本，对正常用户无感），
 * 但慢到 500ms 会让登录接口在压测中成为瓶颈。
 *
 * <h3>{@link #needsRehash} 的实现选择</h3>
 * Spring Security 的 {@code BCryptPasswordEncoder.upgradeEncoding()} 判断的是
 * "当前哈希的 cost 是否低于配置值"。用它而不是自己解析哈希字符串 ——
 * 自己解析要处理 {@code $2a$} / {@code $2b$} / {@code $2y$} 三种前缀，
 * 是一处没必要自己承担的复杂度。
 */
@Component
public class PasswordEncoderAdapter implements PasswordEncoderPort {

    private final PasswordEncoder delegate;
    private final BCryptPasswordEncoder bcrypt;

    public PasswordEncoderAdapter(
            @Value("${webadmin.security.password.bcrypt-strength:10}") int strength) {
        // 越界时回退到安全默认值而不是抛异常：
        // 配置写错不该导致应用起不来，但也不能默默用弱强度。
        int effective = strength < 4 || strength > 16 ? 10 : strength;
        this.bcrypt = new BCryptPasswordEncoder(effective);
        this.delegate = this.bcrypt;
    }

    @Override
    public PasswordHash encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("明文密码不能为空");
        }
        return PasswordHash.of(delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(String rawPassword, PasswordHash encoded) {
        if (rawPassword == null || encoded == null) {
            return false;
        }
        // BCrypt 对畸形哈希会抛 IllegalArgumentException，这里吞掉转成 false。
        // 理由：哈希损坏/格式异常在"校验密码"的语义下就是"不匹配"，
        // 不该让一个 500 暴露给登录接口 —— 那既泄露了实现细节，
        // 也给了攻击者一个区分"用户不存在"与"哈希异常"的侧信道。
        try {
            return delegate.matches(rawPassword, encoded.value());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public boolean needsRehash(PasswordHash encoded) {
        if (encoded == null) {
            return false;
        }
        try {
            return bcrypt.upgradeEncoding(encoded.value());
        } catch (RuntimeException ex) {
            // 无法解析的哈希视为需要重算（下次登录时用新参数重写）
            return true;
        }
    }
}
