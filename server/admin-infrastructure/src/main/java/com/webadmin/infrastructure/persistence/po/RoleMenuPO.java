package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 角色-菜单关联（联合主键 {@code (role_id, menu_id)}，无单列主键）。 */
@Getter
@Setter
@TableName("iam_role_menu")
public class RoleMenuPO {

    private Long tenantId;
    private Long roleId;
    private Long menuId;
    private Instant createTime;
}
