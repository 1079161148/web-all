package com.webadmin.domain.iam.model.role;

import com.webadmin.domain.iam.event.RoleEvent;
import com.webadmin.domain.iam.exception.IllegalRoleStateException;
import com.webadmin.domain.shared.DeptId;
import com.webadmin.domain.shared.DomainEvent;
import com.webadmin.domain.shared.MenuId;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 角色聚合根。
 *
 * <h3>它保护哪些不变量</h3>
 * <ol>
 *   <li><b>内置角色不可删除、不可改标识、不可停用</b>：{@code SUPER_ADMIN} 一旦被删或改名，
 *       系统会进入"没有任何人拥有全部权限"的死锁状态，只能改库恢复。
 *       这类规则必须以聚合方法拒绝，而不是靠前端隐藏按钮</li>
 *   <li><b>CUSTOM 数据范围必须有部门列表</b>：否则会生成 {@code dept_id IN ()} 这种空集合条件 ——
 *       语义上"什么都看不到"，但非常容易被误读成"没有限制"。
 *       这是最危险的一类歧义：一个"看起来像全放开"的空条件</li>
 *   <li><b>菜单与部门集合去重</b>：用 {@code Set} 表达</li>
 * </ol>
 *
 * <h3>Clock 用构造器注入</h3>
 * 理由同 {@code User}：变更方法多、都要发事件，逐个传参会把签名淹没在 {@code Clock} 里。
 */
public class Role {

    private final RoleId id;
    private final TenantId tenantId;
    private final RoleKey roleKey;
    private final Clock clock;

    private String roleName;
    private int sort;
    private DataScope dataScope;
    private boolean builtin;
    private String status;
    private final Set<MenuId> menuIds;
    private final Set<DeptId> deptIds;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Role(RoleId id, TenantId tenantId, RoleKey roleKey, String roleName,
                 int sort, DataScope dataScope, boolean builtin, String status, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.roleKey = Objects.requireNonNull(roleKey, "roleKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.roleName = requireName(roleName);
        this.sort = sort;
        this.dataScope = dataScope == null ? DataScope.SELF : dataScope;
        this.builtin = builtin;
        this.status = status == null ? "ACTIVE" : status;
        this.menuIds = new LinkedHashSet<>();
        this.deptIds = new LinkedHashSet<>();
    }

    // ==================================================================
    // 创建与还原
    // ==================================================================

    public static Role create(RoleId id, TenantId tenantId, RoleKey roleKey,
                              String roleName, int sort, DataScope dataScope, Clock clock) {
        Role role = new Role(id, tenantId, roleKey, roleName, sort, dataScope, false, "ACTIVE", clock);
        role.domainEvents.add(new RoleEvent.Created(
                tenantId, id, roleKey.value(), role.roleName, clock.instant()));
        return role;
    }

    public static Role reconstitute(RoleId id, TenantId tenantId, RoleKey roleKey,
                                    String roleName, int sort, DataScope dataScope,
                                    boolean builtin, String status,
                                    Set<MenuId> menuIds, Set<DeptId> deptIds, Clock clock) {
        Role role = new Role(id, tenantId, roleKey, roleName, sort, dataScope, builtin, status, clock);
        if (menuIds != null) {
            role.menuIds.addAll(menuIds);
        }
        if (deptIds != null) {
            role.deptIds.addAll(deptIds);
        }
        return role;
    }

    // ==================================================================
    // 变更
    // ==================================================================

    /** 修改基础信息（名称、排序）。标识不可改 —— 尤其对内置角色。 */
    public void rename(String newName, int newSort) {
        assertMutable("修改");
        this.roleName = requireName(newName);
        this.sort = newSort;
        domainEvents.add(new RoleEvent.Updated(tenantId, id, roleKey.value(), clock.instant()));
    }

    /**
     * 全量覆盖菜单权限与数据范围。
     *
     * <p>与用户角色分配同样是"全量覆盖"语义：前端权限树提交的是最终勾选结果。
     *
     * <p><b>内置角色的权限允许修改</b>（与名称/标识不同）—— 因为超管往往需要
     * 临时收窄某些权限做验证，禁止修改会迫使绕过系统。但标识与存在性必须保护，
     * 那才是会导致系统不可恢复的部分。<b>保护要精确到"真正不可恢复的操作"，
     * 而不是笼统地"禁止一切修改"</b>。
     */
    public void assignPermissions(Set<MenuId> newMenuIds, DataScope newDataScope,
                                  Set<DeptId> newDeptIds) {
        Objects.requireNonNull(newDataScope, "newDataScope");
        Set<DeptId> targetDepts = newDeptIds == null ? Set.of() : new LinkedHashSet<>(newDeptIds);

        if (newDataScope == DataScope.CUSTOM && targetDepts.isEmpty()) {
            throw new IllegalArgumentException(
                    "数据范围设为「自定义」时必须至少选择一个部门，"
                            + "否则该角色将看不到任何数据（而不是看到全部）");
        }

        this.menuIds.clear();
        if (newMenuIds != null) {
            this.menuIds.addAll(newMenuIds);
        }
        this.deptIds.clear();
        this.deptIds.addAll(targetDepts);
        this.dataScope = newDataScope;

        domainEvents.add(new RoleEvent.PermissionChanged(
                tenantId, id, roleKey.value(),
                menuIds.size(), deptIds.size(), dataScope.name(), clock.instant()));
    }

    public void changeStatus(String newStatus) {
        if (!"ACTIVE".equals(newStatus) && !"SUSPENDED".equals(newStatus)) {
            throw new IllegalArgumentException("角色状态只能是 ACTIVE 或 SUSPENDED");
        }
        assertMutable("停用");
        if (this.status.equals(newStatus)) {
            return;
        }
        this.status = newStatus;
        domainEvents.add(new RoleEvent.StatusChanged(
                tenantId, id, roleKey.value(), newStatus, clock.instant()));
    }

    /** 删除前校验。 */
    public void assertDeletable() {
        if (builtin) {
            throw IllegalRoleStateException.builtinImmutable(roleName);
        }
    }

    public void markDeleted() {
        domainEvents.add(new RoleEvent.Deleted(
                tenantId, id, roleKey.value(), clock.instant()));
    }

    /** 内置角色保护：标识、存在性、状态不允许被改动。 */
    private void assertMutable(String action) {
        if (builtin) {
            throw IllegalRoleStateException.builtinImmutable(roleName);
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public boolean isUsable() {
        return "ACTIVE".equals(status);
    }

    public boolean isSuperAdmin() {
        return roleKey.isSuperAdmin();
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> snapshot = List.copyOf(domainEvents);
        domainEvents.clear();
        return snapshot;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("角色名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("角色名称长度不能超过 64");
        }
        return trimmed;
    }

    // ---- 访问器 ----
    public RoleId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public RoleKey roleKey() {
        return roleKey;
    }

    public String roleName() {
        return roleName;
    }

    public int sort() {
        return sort;
    }

    public DataScope dataScope() {
        return dataScope;
    }

    public boolean builtin() {
        return builtin;
    }

    public String status() {
        return status;
    }

    public Set<MenuId> menuIds() {
        return Set.copyOf(menuIds);
    }

    public Set<DeptId> deptIds() {
        return Set.copyOf(deptIds);
    }
}
