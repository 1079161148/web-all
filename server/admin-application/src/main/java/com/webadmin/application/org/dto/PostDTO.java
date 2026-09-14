package com.webadmin.application.org.dto;

import java.time.Instant;

/**
 * 岗位读模型。
 *
 * @param userCount 关联用户数。与角色一样带上它，是为了让管理员在删除前
 *                  就能看到"这个岗位还有人在用"，而不是点删除后被拒绝。
 */
public record PostDTO(
        Long id,
        Long tenantId,
        String postCode,
        String postName,
        Integer sort,
        String status,
        String remark,
        Integer userCount,
        Instant createTime
) {
}
