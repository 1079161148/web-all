package com.webadmin.application.iam.port;

import com.webadmin.application.iam.dto.RoleDTO;
import com.webadmin.application.iam.query.RolePageQuery;
import com.webadmin.common.api.PageResult;
import java.util.List;
import java.util.Optional;

/** 角色读取端口（CQRS 读侧）。 */
public interface RoleQueryPort {

    PageResult<RoleDTO> page(RolePageQuery query);

    /** 详情（含已授权的菜单 ID 与自定义部门 ID，供权限分配界面回显）。 */
    Optional<RoleDTO> findById(long roleId);

    /**
     * 全部可用角色（供"分配角色"下拉与用户列表筛选使用）。
     *
     * <p>不分页：一个租户的角色数量在<b>几十个</b>量级，分页只会让下拉框变得更难用
     * （用户还得先翻页找角色）。这里以真实数据量做判断，而不是"列表接口都该分页"的教条。
     */
    List<RoleDTO> findUsable();
}
