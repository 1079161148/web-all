package com.webadmin.application.iam.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 角色分页查询条件。 */
@Getter
@Setter
public class RolePageQuery extends PageQuery {

    /** 角色名称，模糊匹配。 */
    private String roleName;

    /** 角色标识，模糊匹配。 */
    private String roleKey;

    /** 状态精确匹配。 */
    private String status;
}
