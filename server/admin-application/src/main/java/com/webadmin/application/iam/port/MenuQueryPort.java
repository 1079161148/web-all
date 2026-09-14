package com.webadmin.application.iam.port;

import com.webadmin.application.iam.dto.MenuDTO;
import java.util.List;
import java.util.Optional;

/**
 * 菜单读取端口（CQRS 读侧）。
 *
 * <p>返回<b>扁平列表</b>（复用已有的 {@code MenuDTO}），由前端建树 ——
 * 与 {@code /auth/menus} 保持一致。菜单总量在千级以内，
 * 一次性返回比"按 parentId 逐层懒加载"更简单也更快
 * （后者在展开深层节点时会产生多次往返）。
 */
public interface MenuQueryPort {

    /** 全部菜单（含按钮权限点），扁平结构。 */
    List<MenuDTO> listAll();

    Optional<MenuDTO> findById(long menuId);
}
