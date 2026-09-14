package com.webadmin.domain.iam.repository;

import com.webadmin.domain.iam.model.menu.Menu;
import com.webadmin.domain.shared.MenuId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 菜单仓储（领域层定义的端口）。
 *
 * <p>注意 {@link #findAllUsable()} <b>不带任何筛选参数</b>：
 * 菜单总量在千级以内（远小于用户量），一次性全量加载后在内存建树，
 * 比"按 parentId 递归查库"或"按角色过滤后查库"都要快且简单。
 *
 * <p>这是<b>有意的反直觉选择</b>：直觉上"按角色只查需要的菜单"更省资源，
 * 但实际上菜单数据小、变化少、可长缓存，而递归查询会带来 N 次往返。
 * 用真实数据量做判断，而不是靠直觉优化。
 */
public interface MenuRepository {

    Optional<Menu> findById(MenuId id);

    /** 全部可用菜单（未删除且状态正常）。 */
    List<Menu> findAllUsable();

    /** 按 ID 集合加载（用于"用户拥有的菜单"场景：先用角色-菜单关联查出 ID，再批量取菜单）。 */
    List<Menu> findAllByIds(Collection<MenuId> ids);

    void save(Menu menu);

    void delete(MenuId id);

    /** 是否存在子节点（删除前校验）。 */
    boolean hasChildren(MenuId id);
}
