package com.webadmin.infrastructure.persistence.converter;

import com.webadmin.domain.iam.model.role.DataScope;
import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.model.role.RoleKey;
import com.webadmin.domain.shared.DeptId;
import com.webadmin.domain.shared.MenuId;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.infrastructure.persistence.po.RolePO;
import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 角色聚合 ↔ PO 转换。 */
@Component
public class RoleConverter {

    public RolePO toPO(Role role) {
        RolePO po = new RolePO();
        po.setId(role.id().value());
        po.setTenantId(role.tenantId().value());
        po.setRoleName(role.roleName());
        po.setRoleKey(role.roleKey().value());
        po.setSort(role.sort());
        po.setDataScope(role.dataScope().name());
        po.setBuiltin(role.builtin());
        po.setStatus(role.status());
        return po;
    }

    public Role toDomain(RolePO po, Set<Long> menuIds, Set<Long> deptIds, Clock clock) {
        if (po == null) {
            return null;
        }
        Set<MenuId> menus = menuIds == null ? Set.of()
                : menuIds.stream().map(MenuId::of).collect(Collectors.toSet());
        Set<DeptId> depts = deptIds == null ? Set.of()
                : deptIds.stream().map(DeptId::of).collect(Collectors.toSet());
        return Role.reconstitute(
                RoleId.of(po.getId()),
                TenantId.ofPersisted(po.getTenantId() == null ? 0L : po.getTenantId()),
                RoleKey.ofPersisted(po.getRoleKey()),
                po.getRoleName(),
                po.getSort() == null ? 0 : po.getSort(),
                parseDataScope(po.getDataScope()),
                Boolean.TRUE.equals(po.getBuiltin()),
                po.getStatus(),
                menus,
                depts,
                clock);
    }

    public void mergeIntoPO(Role role, RolePO po) {
        po.setRoleName(role.roleName());
        po.setSort(role.sort());
        po.setDataScope(role.dataScope().name());
        po.setStatus(role.status());
    }

    /**
     * 解析数据范围字符串。
     *
     * <p>脏数据（非法枚举值）回退到 {@link DataScope#SELF} 而不是抛异常：
     * 这是<b>fail-closed</b> 的兜底 —— 把无法识别的范围当作"最窄"处理，
     * 最多让某些人暂时看不到数据（可发现、可修复），
     * 而若回退到 {@code ALL} 则等于静默放开全部数据（不可发现）。
     */
    private static DataScope parseDataScope(String value) {
        if (value == null || value.isBlank()) {
            return DataScope.SELF;
        }
        try {
            return DataScope.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return DataScope.SELF;
        }
    }
}
