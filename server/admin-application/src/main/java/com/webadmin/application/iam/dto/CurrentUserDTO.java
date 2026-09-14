package com.webadmin.application.iam.dto;

import java.time.Instant;

/** 当前登录用户信息（供前端初始化用户态）。 */
public record CurrentUserDTO(
        Long userId,
        Long tenantId,
        String username,
        String nickname,
        String avatar,
        String email,
        /** 手机号：**字段级权限**保护的示例字段。
         *  普通角色只能拿到脱敏值（138****8888），具权限者拿到完整值。
         *  脱敏在序列化层完成（见 {@code @FieldPermission}），业务代码无感知。 */
        String phone,
        Long deptId,
        String status,
        String loginIp,
        Instant loginTime
) {
}
