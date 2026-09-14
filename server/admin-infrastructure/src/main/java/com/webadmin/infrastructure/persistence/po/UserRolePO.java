package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户-角色关联。
 *
 * <p>这张表<b>没有</b> {@code @TableId}（主键是 {@code (user_id, role_id)} 联合主键），
 * 因此不能走 {@code BaseMapper.selectById} 系列方法，只能用 Wrapper 条件查询 ——
 * 这是符合预期的：关联表的操作语义就是"按条件批量增删"，不是"按主键取一行"。
 *
 * <p>它带 {@code tenant_id}（见 V1.0.1 的决策说明），因此租户拦截器照常生效，
 * 无需任何例外配置。
 */
@Getter
@Setter
@TableName("iam_user_role")
public class UserRolePO {

    private Long tenantId;
    private Long userId;
    private Long roleId;
    private Instant createTime;
}
