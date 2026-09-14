package com.webadmin.domain.iam.model.menu;

import com.webadmin.domain.shared.MenuId;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单（<b>L2 支撑域模型</b>，不是聚合根）。
 *
 * <h3>为什么它不按 L3 建模</h3>
 * 按设计文档 §4.1 的判据："<b>业务规则能用一句 if 表达完的模块，不配 DDD</b>"。
 * 菜单的规则只有两条 —— 类型必须是 DIR/MENU/BUTTON、同级排序值决定顺序 ——
 * 既没有状态机，也没有跨字段不变量，更不产生领域事件。
 * 硬套"聚合根 + 值对象 + 事件"只会增加文件数量而不增加任何表达力。
 *
 * <p>因此这里是一个<b>可变实体</b>（有 setter，但不暴露非法赋值）：
 * 字段通过构造与显式方法修改，树形结构的组装交给查询层
 * （{@code MenuQueryPort} 一次性查出全部菜单后在内存里建树，避免递归查库）。
 *
 * <h3>特别说明 perms 的作用</h3>
 * {@code perms} 就是权限码（如 {@code iam:user:add}）。它有两个消费方：
 * <ul>
 *   <li>鉴权：{@code PermissionService.hasPermission()} 通过"用户 → 角色 → 菜单.perms"求并集</li>
 *   <li>前端：菜单树返回给前端后，前端把它注册为按钮权限指令可比对的集合</li>
 * </ul>
 * <b>但后端鉴权绝不依赖前端传来的任何东西</b> —— 权限集合由后端自己算，
 * 前端那份只用于隐藏按钮，绕过它也不会获得任何额外的服务端能力。
 */
public class Menu {

    private final MenuId id;
    private final MenuId parentId;
    private final String menuName;
    private final MenuType menuType;
    private final String path;
    private final String component;
    private final String perms;
    private final String icon;
    private final int sort;
    private final boolean visible;
    private final boolean keepAlive;
    private final boolean alwaysShow;
    private final String status;

    private Menu(MenuId id, MenuId parentId, String menuName, MenuType menuType,
                 String path, String component, String perms, String icon, int sort,
                 boolean visible, boolean keepAlive, boolean alwaysShow, String status) {
        this.id = id;
        this.parentId = parentId;
        this.menuName = menuName;
        this.menuType = menuType;
        this.path = path;
        this.component = component;
        this.perms = perms;
        this.icon = icon;
        this.sort = sort;
        this.visible = visible;
        this.keepAlive = keepAlive;
        this.alwaysShow = alwaysShow;
        this.status = status;
    }

    public static Menu of(MenuId id, MenuId parentId, String menuName, MenuType menuType,
                          String path, String component, String perms, String icon, int sort,
                          boolean visible, boolean keepAlive, boolean alwaysShow, String status) {
        if (menuName == null || menuName.isBlank()) {
            throw new IllegalArgumentException("菜单名称不能为空");
        }
        if (menuType == null) {
            throw new IllegalArgumentException("菜单类型不能为空");
        }
        // 这是本模型唯一的跨字段不变量：按钮没有路由，菜单必须有组件。
        // 不校验的话，前端拿到 component 为 null 的菜单会在 addRoute 时抛错，
        // 而且报错位置离根因（数据录错）很远。
        if (menuType.requiresComponent() && (component == null || component.isBlank())) {
            throw new IllegalArgumentException(
                    "菜单/目录必须配置组件路径，否则前端无法完成路由映射：" + menuName);
        }
        if (menuType.requiresPermission() && (perms == null || perms.isBlank())) {
            throw new IllegalArgumentException("按钮必须配置权限码：" + menuName);
        }
        return new Menu(id, parentId, menuName, menuType, path, component, perms, icon, sort,
                visible, keepAlive, alwaysShow, status == null ? "ACTIVE" : status);
    }

    public boolean isUsable() {
        return "ACTIVE".equals(status);
    }

    /** 是否为可用于鉴权的权限点（按钮且状态正常）。 */
    public boolean isGrantablePermission() {
        return isUsable() && perms != null && !perms.isBlank();
    }

    // ---- 访问器 ----
    public MenuId id() {
        return id;
    }

    public MenuId parentId() {
        return parentId;
    }

    public String menuName() {
        return menuName;
    }

    public MenuType menuType() {
        return menuType;
    }

    public String path() {
        return path;
    }

    public String component() {
        return component;
    }

    public String perms() {
        return perms;
    }

    public String icon() {
        return icon;
    }

    public int sort() {
        return sort;
    }

    public boolean visible() {
        return visible;
    }

    public boolean keepAlive() {
        return keepAlive;
    }

    public boolean alwaysShow() {
        return alwaysShow;
    }

    public String status() {
        return status;
    }

    /**
     * 在内存中把扁平菜单列表组装成树。
     *
     * <p>为什么放在领域侧而不是查询层：建树规则（父不存在时挂到根、按 sort 稳定排序）
     * 是<b>菜单语义的一部分</b>，不是 SQL 关注点。放在这里还能被单元测试直接覆盖，
     * 不需要起数据库。
     */
    public static List<MenuTreeNode> buildTree(List<Menu> flatMenus) {
        java.util.Map<Long, MenuTreeNode> nodes = new java.util.LinkedHashMap<>();
        for (Menu menu : flatMenus) {
            nodes.put(menu.id().value(), new MenuTreeNode(menu));
        }
        List<MenuTreeNode> roots = new ArrayList<>();
        for (MenuTreeNode node : nodes.values()) {
            long parentId = node.menu.parentId().value();
            MenuTreeNode parent = parentId == 0L ? null : nodes.get(parentId);
            if (parent == null) {
                // 父节点不存在（被禁用或已删除）时挂到根，而不是丢弃 ——
                // 丢弃会让子菜单"凭空消失"，用户只会看到一个空菜单，无从排查
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        sortRecursively(roots);
        return roots;
    }

    private static void sortRecursively(List<MenuTreeNode> nodes) {
        nodes.sort(java.util.Comparator.comparingInt(n -> n.menu.sort()));
        for (MenuTreeNode node : nodes) {
            sortRecursively(node.children);
        }
    }

    /** 树节点（仅用于承载结构，不持久化）。 */
    public static final class MenuTreeNode {

        private final Menu menu;
        private final List<MenuTreeNode> children = new ArrayList<>();

        MenuTreeNode(Menu menu) {
            this.menu = menu;
        }

        public Menu menu() {
            return menu;
        }

        public List<MenuTreeNode> children() {
            return List.copyOf(children);
        }
    }
}
