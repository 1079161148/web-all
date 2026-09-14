package com.webadmin.application.org.port;

import com.webadmin.application.org.dto.PostDTO;
import com.webadmin.application.org.query.PostPageQuery;
import com.webadmin.common.api.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * 岗位读写端口。
 *
 * <h3>为什么岗位只有一个端口，而字典拆成了读/写两个</h3>
 * 判断依据是<b>读侧是否有独立且复杂的逻辑</b>，而不是"规范上应该拆开"：
 * <ul>
 *   <li>字典的读侧要处理「平台默认 + 租户覆盖」的两级合并，还要承载
 *       "只要求登录、不要求权限码"的 useDict 入口 —— 它有独立的复杂度，
 *       值得单独一个类专门照看</li>
 *   <li>岗位的读侧就是一条 {@code selectList} + 一次批量计数，
 *       拆开只会多一个文件与一次注入</li>
 * </ul>
 * <b>按复杂度决定结构，而不是按对称性。</b>
 */
public interface PostPort {

    PageResult<PostDTO> page(PostPageQuery query);

    Optional<PostDTO> findById(long id);

    /** 全部启用岗位（供用户表单的岗位多选使用，量级在几十个以内，不分页）。 */
    List<PostDTO> findUsable();

    boolean codeExists(String postCode, Long excludeId);

    Long insert(String postCode, String postName, Integer sort, String status, String remark);

    void update(long id, String postCode, String postName, Integer sort,
                String status, String remark);

    long countUsers(long postId);

    void delete(long id);
}
