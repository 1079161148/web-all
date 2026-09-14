package com.webadmin.application.iam.dto;

import com.webadmin.domain.iam.model.menu.Menu;

/**
 * 菜单 DTO（扁平结构，前端建树）。
 *
 * @param id         菜单 ID
 * @param parentId   父菜单 ID，0 为根
 * @param menuName   显示名称
 * @param menuType   DIR / MENU / BUTTON
 * @param path       路由地址
 * @param component  组件路径（前端用 {@code import.meta.glob} 查表映射成真实组件，
 *                   因此后端返回的是**字符串**而非任何代码 —— 这保证了
 *                   "菜单数据无法注入可执行内容"）
 * @param perms      权限码
 * @param icon       图标名
 * @param sort       排序
 * @param visible    是否显示
 * @param keepAlive  是否缓存页面
 * @param alwaysShow 单子节点时是否仍显示父级
 */
public record MenuDTO(
        Long id,
        Long parentId,
        String menuName,
        String menuType,
        String path,
        String component,
        String perms,
        String icon,
        Integer sort,
        Boolean visible,
        Boolean keepAlive,
        Boolean alwaysShow
) {

    public static MenuDTO from(Menu menu) {
        return new MenuDTO(
                menu.id().value(),
                menu.parentId().value(),
                menu.menuName(),
                menu.menuType().name(),
                menu.path(),
                menu.component(),
                menu.perms(),
                menu.icon(),
                menu.sort(),
                menu.visible(),
                menu.keepAlive(),
                menu.alwaysShow());
    }
}
