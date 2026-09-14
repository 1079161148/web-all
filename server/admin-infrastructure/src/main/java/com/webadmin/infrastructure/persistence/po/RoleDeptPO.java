package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 角色-自定义数据范围部门（联合主键 {@code (role_id, dept_id)}）。 */
@Getter
@Setter
@TableName("iam_role_dept")
public class RoleDeptPO {

    private Long tenantId;
    private Long roleId;
    private Long deptId;
    private Instant createTime;
}
