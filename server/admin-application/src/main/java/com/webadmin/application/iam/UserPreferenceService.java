package com.webadmin.application.iam;

import com.webadmin.application.iam.port.UserPreferencePort;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户偏好应用服务（表格列布局、页面级开关等用户级 KV 配置）。
 *
 * <h3>三条底线（服务器的责任，其余信任前端契约）</h3>
 * <ol>
 *   <li><b>键格式</b>：{@code [a-z0-9:_.-]} 且 ≤ 64 字符 —— 键会进入
 *       存储索引与日志，不受控的键等于给每个用户开放了任意命名空间；</li>
 *   <li><b>值上限 8KB</b>：偏好最多是"一张表的完整列布局"。
 *       上限不是猜的 —— 一张 30 列的表格全量布局 JSON 远小于 1KB，
 *       8KB 已是十倍余量，既能拦住"把文件存进偏好"的滥用，又绝不会误伤；</li>
 *   <li><b>身份来自令牌</b>：偏好永远写在"当前用户"名下 ——
 *       没有任何端点允许指定他人 ID，这类参数是水平越权的经典入口。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    /** 偏好值的字节上限（UTF-8）。 */
    private static final int MAX_VALUE_BYTES = 8 * 1024;

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9:_.-]{1,64}$");

    private final UserPreferencePort preferencePort;
    private final CurrentUserPort currentUserPort;

    /** 读取当前用户的偏好；未设置返回 {@code null}。 */
    public String find(String key) {
        requireValidKey(key);
        var current = currentUserPort.requireCurrentUser();
        return preferencePort.find(TenantContext.require(), current.userId(), key);
    }

    /** 写入当前用户的偏好（upsert）。 */
    public void save(String key, String value) {
        requireValidKey(key);
        if (value != null && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "偏好值超出上限（8KB）");
        }
        var current = currentUserPort.requireCurrentUser();
        preferencePort.save(TenantContext.require(), current.userId(), key, value);
    }

    private static void requireValidKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "偏好键格式非法（允许小写字母、数字与 : _ . -，最长 64 字符）");
        }
    }
}
