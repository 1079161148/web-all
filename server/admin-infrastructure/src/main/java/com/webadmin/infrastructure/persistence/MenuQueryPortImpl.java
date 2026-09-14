package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.application.iam.dto.MenuDTO;
import com.webadmin.application.iam.port.MenuQueryPort;
import com.webadmin.infrastructure.persistence.mapper.MenuMapper;
import com.webadmin.infrastructure.persistence.po.MenuPO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 菜单读侧实现。
 *
 * <p>直接读 PO 而不是走 {@code MenuRepository}（领域实体）：
 * 读侧只需要展示字段、不做任何业务判断，把领域实体拉进来只是多一层
 * "实体 → DTO"的转换。<b>读侧走直路，写侧走聚合</b> ——
 * 这是 CQRS 分工在开发效率上的直接体现。
 */
@Repository
@RequiredArgsConstructor
public class MenuQueryPortImpl implements MenuQueryPort {

    private final MenuMapper menuMapper;

    @Override
    public List<MenuDTO> listAll() {
        return menuMapper.selectList(new LambdaQueryWrapper<MenuPO>()
                        .orderByAsc(MenuPO::getParentId)
                        .orderByAsc(MenuPO::getSort)
                        .orderByAsc(MenuPO::getId))
                .stream()
                .map(MenuQueryPortImpl::toDTO)
                .toList();
    }

    @Override
    public Optional<MenuDTO> findById(long menuId) {
        return Optional.ofNullable(menuMapper.selectById(menuId))
                .map(MenuQueryPortImpl::toDTO);
    }

    private static MenuDTO toDTO(MenuPO po) {
        return new MenuDTO(
                po.getId(),
                po.getParentId(),
                po.getMenuName(),
                po.getMenuType(),
                po.getPath(),
                po.getComponent(),
                po.getPerms(),
                po.getIcon(),
                po.getSort(),
                Boolean.TRUE.equals(po.getVisible()),
                Boolean.TRUE.equals(po.getKeepAlive()),
                Boolean.TRUE.equals(po.getAlwaysShow()));
    }
}
