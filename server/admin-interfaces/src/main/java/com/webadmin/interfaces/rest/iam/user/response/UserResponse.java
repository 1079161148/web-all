package com.webadmin.interfaces.rest.iam.user.response;

import com.webadmin.application.iam.dto.UserDTO;
import com.webadmin.common.fieldpermission.FieldPermission;
import com.webadmin.common.fieldpermission.MaskStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 用户信息（对外契约）。
 *
 * <h3>字段级权限的示范位</h3>
 * {@code phone} 与 {@code email} 标了 {@link FieldPermission}：
 * 只有 {@code SUPER_ADMIN} 能看到完整值，其余角色拿到的是
 * {@code 138****8888} 这样的脱敏结果。
 *
 * <p>脱敏由 {@code FieldPermissionResponseAdvice} 在响应写出前自动完成，
 * <b>业务代码里看不到任何脱敏逻辑</b> —— 这正是设计文档 §7.4
 * "不侵入业务代码，加注解即可"的兑现方式。
 *
 * <p>⚠️ 这套机制<b>不会因为注解写错而报错</b>：删掉注解、拼错角色标识、
 * 或把 advice 从容器里移除，接口都会照常返回<完整值/full value>，
 * 只是敏感数据悄悄泄露了。因此它必须由集成测试兜底 ——
 * 断言"非超管角色拿到的手机号是脱敏的"。
 */
@Schema(description = "用户信息")
public record UserResponse(

        @Schema(description = "用户 ID")
        Long id,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "所属部门 ID")
        Long deptId,

        @Schema(description = "所属部门名称")
        String deptName,

        @Schema(description = "登录账号")
        String username,

        @Schema(description = "昵称/姓名")
        String nickname,

        @Schema(description = "手机号。非超管角色只能看到前 3 后 4 位")
        @FieldPermission(visibleFor = {"SUPER_ADMIN"}, maskStrategy = MaskStrategy.PHONE)
        String phone,

        @Schema(description = "邮箱。非超管角色只能看到首字母与域名")
        @FieldPermission(visibleFor = {"SUPER_ADMIN"}, maskStrategy = MaskStrategy.EMAIL)
        String email,

        @Schema(description = "性别：0=男 1=女 2=未知")
        Integer sex,

        @Schema(description = "头像 URL")
        String avatar,

        @Schema(description = "状态：ACTIVE=正常 SUSPENDED=停用 LOCKED=锁定")
        String status,

        @Schema(description = "最后登录 IP")
        String loginIp,

        @Schema(description = "最后登录时间（UTC）")
        Instant loginTime,

        @Schema(description = "已分配的角色 ID 列表")
        List<Long> roleIds,

        @Schema(description = "已分配的角色名称（顿号分隔）")
        String roleNames,

        @Schema(description = "创建时间（UTC）")
        Instant createTime
) {

    /** 由应用层读模型转换。 */
    public static UserResponse from(UserDTO dto) {
        if (dto == null) {
            return null;
        }
        return new UserResponse(
                dto.id(), dto.tenantId(), dto.deptId(), dto.deptName(),
                dto.username(), dto.nickname(), dto.phone(), dto.email(),
                dto.sex(), dto.avatar(), dto.status(), dto.loginIp(), dto.loginTime(),
                dto.roleIds(), dto.roleNames(), dto.createTime());
    }
}
