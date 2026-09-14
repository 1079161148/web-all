package com.webadmin.infrastructure.persistence.converter;

import com.webadmin.domain.iam.model.menu.Menu;
import com.webadmin.domain.iam.model.menu.MenuType;
import com.webadmin.domain.shared.MenuId;
import com.webadmin.infrastructure.persistence.po.MenuPO;
import org.springframework.stereotype.Component;

/** 菜单实体 ↔ PO 转换。 */
@Component
public class MenuConverter {

    public MenuPO toPO(Menu menu) {
        MenuPO po = new MenuPO();
        po.setId(menu.id().value());
        po.setParentId(menu.parentId().value());
        po.setMenuName(menu.menuName());
        po.setMenuType(menu.menuType().name());
        po.setPath(menu.path());
        po.setComponent(menu.component());
        po.setPerms(menu.perms());
        po.setIcon(menu.icon());
        po.setSort(menu.sort());
        po.setVisible(menu.visible());
        po.setKeepAlive(menu.keepAlive());
        po.setAlwaysShow(menu.alwaysShow());
        po.setStatus(menu.status());
        return po;
    }

    public Menu toDomain(MenuPO po) {
        return po == null ? null : Menu.of(
                MenuId.of(po.getId()),
                // parent_id 为 null 视为根：数据库列 NOT NULL DEFAULT 0，理论上不会为 null，
                // 但迁移或人工改库可能留下 null，这里兜底避免建树时 NPE
                MenuId.of(po.getParentId() == null ? 0L : po.getParentId()),
                po.getMenuName(),
                MenuType.valueOf(po.getMenuType()),
                po.getPath(),
                po.getComponent(),
                po.getPerms(),
                po.getIcon(),
                po.getSort() == null ? 0 : po.getSort(),
                Boolean.TRUE.equals(po.getVisible()),
                Boolean.TRUE.equals(po.getKeepAlive()),
                Boolean.TRUE.equals(po.getAlwaysShow()),
                po.getStatus());
    }
}
