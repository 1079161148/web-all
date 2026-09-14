package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.domain.iam.model.menu.Menu;
import com.webadmin.domain.iam.repository.MenuRepository;
import com.webadmin.domain.shared.MenuId;
import com.webadmin.infrastructure.persistence.converter.MenuConverter;
import com.webadmin.infrastructure.persistence.mapper.MenuMapper;
import com.webadmin.infrastructure.persistence.po.MenuPO;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 菜单仓储实现（无需乐观锁冲突处理：菜单变更频率极低，且冲突影响可忽略）。 */
@Repository
@RequiredArgsConstructor
public class MenuRepositoryImpl implements MenuRepository {

    private final MenuMapper menuMapper;
    private final MenuConverter menuConverter;

    @Override
    public Optional<Menu> findById(MenuId id) {
        return Optional.ofNullable(menuMapper.selectById(id.value()))
                .map(menuConverter::toDomain);
    }

    @Override
    public List<Menu> findAllUsable() {
        // 按 parentId、sort 排序在第 3 节索引 idx_menu_parent 上可直接命中，
        // 避免把排序放到内存里做（菜单量虽小，但不该养成依赖"量小"的习惯）。
        return menuMapper.selectList(new LambdaQueryWrapper<MenuPO>()
                        .eq(MenuPO::getStatus, "ACTIVE")
                        .orderByAsc(MenuPO::getParentId)
                        .orderByAsc(MenuPO::getSort))
                .stream()
                .map(menuConverter::toDomain)
                .toList();
    }

    @Override
    public List<Menu> findAllByIds(Collection<MenuId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectByIds(ids.stream().map(MenuId::value).toList())
                .stream()
                .map(menuConverter::toDomain)
                .toList();
    }

    @Override
    public void save(Menu menu) {
        MenuPO po = menuConverter.toPO(menu);
        if (menuMapper.selectById(menu.id().value()) == null) {
            menuMapper.insert(po);
        } else {
            menuMapper.updateById(po);
        }
    }

    @Override
    public void delete(MenuId id) {
        menuMapper.deleteById(id.value());
    }

    @Override
    public boolean hasChildren(MenuId id) {
        return menuMapper.exists(new LambdaQueryWrapper<MenuPO>()
                .eq(MenuPO::getParentId, id.value()));
    }
}
