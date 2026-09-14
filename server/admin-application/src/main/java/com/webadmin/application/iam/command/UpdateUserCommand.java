package com.webadmin.application.iam.command;

import java.util.Set;

/**
 * 修改用户命令。
 *
 * <h3>刻意不含密码与状态</h3>
 * 密码与状态各自有独立入口（重置密码 / 启停）。把它们塞进"通用修改"里会带来两个问题：
 * <ul>
 *   <li><b>权限粒度失守</b>：修改资料的权限和重置密码、停用账号显然不是一回事，
 *       合并后无法分别授权</li>
 *   <li><b>误伤</b>：前端表单只要漏传一个字段，就可能把状态意外改掉</li>
 * </ul>
 * <b>一个接口只做一件事，权限才有意义。</b>
 *
 * <p>同样不含用户名：用户名是登录凭据与审计主体标识，改名会让历史日志失去指向。
 * 真要支持改名，应当是独立且需二次确认的特权操作。
 */
public record UpdateUserCommand(
        String nickname,
        String email,
        String phone,
        Long deptId,
        Integer sex,
        String avatar,
        Set<Long> roleIds
) {
}
