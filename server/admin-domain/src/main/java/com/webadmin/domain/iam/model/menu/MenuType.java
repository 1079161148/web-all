package com.webadmin.domain.iam.model.menu;

/**
 * 菜单类型。
 *
 * <p>三种类型的差异不只是"显示方式"，而是<b>需要哪些必填字段</b>：
 * <ul>
 *   <li>{@link #DIR} 目录：只有路由壳，无组件（在 Vue Router 里表现为带 children 的父路由）</li>
 *   <li>{@link #MENU} 菜单：可直接访问的页面，必须有 component</li>
 *   <li>{@link #BUTTON} 按钮：没有路由、没有组件，只有权限码</li>
 * </ul>
 * 把"需要哪些字段"的判定放在枚举上，是为了让校验规则与类型定义同源 ——
 * 否则新增一种类型（比如"外链"）时，最容易漏掉的恰恰是散在各处的校验分支。
 */
public enum MenuType {

    /** 目录。 */
    DIR,

    /** 菜单（页面）。 */
    MENU,

    /** 按钮（权限点）。 */
    BUTTON,
    ;

    /** 是否需要组件路径（DIR 不需要渲染组件，MENU 必须有）。 */
    public boolean requiresComponent() {
        return this == MENU;
    }

    /** 是否需要权限码（只有按钮是权限点）。 */
    public boolean requiresPermission() {
        return this == BUTTON;
    }

    /** 是否参与前端路由生成（按钮不参与）。 */
    public boolean routable() {
        return this == DIR || this == MENU;
    }
}
