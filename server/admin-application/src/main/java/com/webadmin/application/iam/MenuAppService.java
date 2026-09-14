package com.webadmin.application.iam;

import com.webadmin.common.error.BizException;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.menu.Menu;
import com.webadmin.domain.iam.model.menu.MenuType;
import com.webadmin.domain.iam.repository.MenuRepository;
import com.webadmin.domain.shared.IdGenerator;
import com.webadmin.domain.shared.MenuId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 菜单应用服务（L2 支撑域，事务脚本）。
 *
 * <h3>为什么它是"事务脚本"而不是富领域模型</h3>
 * 按设计文档 §4.1 的判据："业务规则能用一句 if 表达完的模块，不配 DDD"。
 * 菜单的规则只有两条 —— 类型合法、优先删掉有子节点的菜单 ——
 * 没有状态机、没有跨字段不变量、不产生领域事件。
 *
 * <p>但菜单仍<b>通过 {@link Menu} 实体</b>写入而不是直接操作 PO：
 * 因为实体的构造校验（"菜单必须有 component"、"按钮必须有权限码"）
 * 本身就是有价值的规则，值得被保留在一个可被单元测试直接覆盖的地方。
 * <b>用实体做校验、用服务做编排</b> —— 这是 L2 的合理形态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuAppService {

    private final MenuRepository menuRepository;
    private final IdGenerator idGenerator;

    @Transactional
    public Long createMenu(Long parentId, String menuName, String menuType, String path,
                           String component, String perms, String icon, Integer sort,
                           Boolean visible, Boolean keepAlive, Boolean alwaysShow) {
        MenuId id = idGenerator.nextMenuId();
        Menu menu = Menu.of(
                id, MenuId.of(parentId == null ? 0L : parentId), menuName,
                parseType(menuType), path, component, perms, icon,
                sort == null ? 0 : sort,
                visible == null || visible,
                keepAlive == null || keepAlive,
                alwaysShow != null && alwaysShow,
                "ACTIVE");
        menuRepository.save(menu);
        log.info("创建菜单 menuId={} name={} type={}", id.value(), menuName, menuType);
        return id.value();
    }

    /**
     * 修改菜单。
     *
     * <p>与创建共用同一套 {@link Menu#of} 校验 —— 修改时若把菜单类型从"按钮"
     * 改成"菜单"却忘了填 component，必须同样被拒绝。
     * <b>校验只写一处，才不会出现"创建时校验、修改时漏校验"的经典漏洞。</b>
     */
    @Transactional
    public void updateMenu(long menuId, Long parentId, String menuName, String menuType,
                           String path, String component, String perms, String icon, Integer sort,
                           Boolean visible, Boolean keepAlive, Boolean alwaysShow) {
        MenuId id = MenuId.of(menuId);
        Menu existing = menuRepository.findById(id)
                .orElseThrow(() -> new BizException(IamErrorCode.MENU_NOT_FOUND, "菜单不存在"));

        if (parentId != null && parentId == menuId) {
            throw new BizException(IamErrorCode.MENU_INVALID, "不能把菜单挂到自己下面");
        }

        Menu updated = Menu.of(id,
                MenuId.of(parentId == null ? existing.parentId().value() : parentId),
                menuName, parseType(menuType), path, component, perms, icon,
                sort == null ? existing.sort() : sort,
                visible == null ? existing.visible() : visible,
                keepAlive == null ? existing.keepAlive() : keepAlive,
                alwaysShow == null ? existing.alwaysShow() : alwaysShow,
                existing.status());

        menuRepository.save(updated);
        log.info("修改菜单 menuId={} name={}", menuId, menuName);
    }

    @Transactional
    public void deleteMenu(long menuId) {
        MenuId id = MenuId.of(menuId);
        // 有子节点时拒绝删除。级联删除是更危险的选择：
        // 一次误点就会连带删掉整个子树及其权限点，而这些权限点可能正被角色引用，
        // 结果是"某些角色的权限莫名其妙少了"。
        // 要求先处理子节点，虽然多一步操作，但让影响面始终可控。
        if (menuRepository.hasChildren(id)) {
            throw new BizException(IamErrorCode.MENU_HAS_CHILDREN,
                    "该菜单下仍有子节点，请先删除或移出子节点");
        }
        menuRepository.delete(id);
        log.info("删除菜单 menuId={}", menuId);
    }

    private MenuType parseType(String menuType) {
        if (menuType == null || menuType.isBlank()) {
            throw new BizException(IamErrorCode.MENU_INVALID, "菜单类型不能为空");
        }
        try {
            return MenuType.valueOf(menuType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException(IamErrorCode.MENU_INVALID,
                    "无法识别的菜单类型：" + menuType + "（应为 DIR / MENU / BUTTON）");
        }
    }
}
