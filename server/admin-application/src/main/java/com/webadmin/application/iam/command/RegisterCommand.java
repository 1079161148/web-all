package com.webadmin.application.iam.command;

/**
 * 自助注册命令。
 *
 * <h3>与 {@link CreateUserCommand} 的区别</h3>
 * 后者是<b>管理员</b>建号（可以指定角色、部门、初始密码），本命令是<b>任何人</b>
 * 都能发起的入口 —— 这决定了它的字段必须最小化：
 * <ul>
 *   <li><b>没有 roleIds</b>：注册者不能给自己要权限。新账号一律无角色，
 *       由管理员在用户管理里授权（这是本功能最重要的安全边界）</li>
 *   <li><b>没有 deptId / sex / status</b>：这些是管理信息，注册者无权决定</li>
 *   <li><b>密码必填</b>：不像管理员建号那样允许"使用平台初始密码"——
 *       若允许留空，等于批量制造共享同一初始密码的账号</li>
 * </ul>
 *
 * <h3>为什么带验证码字段</h3>
 * 注册是<b>公开的写入入口</b>（比登录更容易被滥用：登录有账号锁定兜底，
 * 注册没有）。验证码 + IP 限流是挡住批量刷号的最低配置。
 *
 * @param username    登录账号（租户内唯一）
 * @param nickname    昵称/姓名（可空，默认与账号同名）
 * @param rawPassword 明文密码。<b>只在本次调用内存活</b>，不落日志、不持久化
 * @param email       邮箱（可选；本系统尚无邮件验证能力，因此不做唯一性约束）
 * @param captchaId   图形验证码 id
 * @param captchaCode 用户输入的验证码（一次性）
 */
public record RegisterCommand(
        String username,
        String nickname,
        String rawPassword,
        String email,
        String captchaId,
        String captchaCode
) {
}
