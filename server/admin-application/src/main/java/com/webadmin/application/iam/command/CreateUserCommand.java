package com.webadmin.application.iam.command;

import java.util.Set;

/**
 * 创建用户命令。
 *
 * @param username 登录账号（租户内唯一）
 * @param nickname 昵称/姓名
 * @param rawPassword 明文密码。为 {@code null} 时使用平台配置的初始密码
 *                    （{@code sys.user.init-password}）。<b>只在本次调用内存活</b>
 * @param email 邮箱
 * @param phone 手机号
 * @param deptId 所属部门，可为空
 * @param sex 性别
 * @param roleIds 初始角色
 */
public record CreateUserCommand(
        String username,
        String nickname,
        String rawPassword,
        String email,
        String phone,
        Long deptId,
        Integer sex,
        Set<Long> roleIds
) {
}
