package com.webadmin.application.iam.port;

import com.webadmin.application.iam.dto.UserDTO;
import com.webadmin.application.iam.query.UserPageQuery;
import com.webadmin.common.api.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * 用户读取端口（CQRS 读侧）。
 *
 * <h3>为什么与 {@code UserRepository} 并存</h3>
 * 两者看起来都在"查用户"，但职责完全不同：
 * <ul>
 *   <li>{@code UserRepository} 是<b>写侧的加载入口</b>：返回完整聚合，
 *       供业务方法调用后落库。它必须加载所有字段（含密码哈希），
 *       因为聚合的每个方法都可能用到</li>
 *   <li>本端口是<b>读侧</b>：直接返回面向展示的 DTO，可以在 SQL 里联表、
 *       聚合、只取需要的列。它<b>不返回聚合</b>，因此不可能被误用于写操作</li>
 * </ul>
 *
 * <p>合并成一个接口的实际后果是：列表查询也被迫加载聚合与密码哈希，
 * 而"列表只需要展示字段"这个事实就丢失了。
 *
 * <h3>{@link #page} 上的数据权限</h3>
 * 实现必须在 Mapper 方法上标注 {@code @DataScope}，
 * 否则列表会绕过部门数据范围 —— 这是最容易被漏掉的一处，
 * 因为"列表看起来正常，只是多了几行别人的数据"。</p>
 */
public interface UserQueryPort {

    /** 分页查询（自动施加租户隔离 + 行级数据权限）。 */
    PageResult<UserDTO> page(UserPageQuery query);

    /** 查询详情（含角色）。 */
    Optional<UserDTO> findById(long userId);

    /** 查询某角色下的用户 ID（用于角色删除前的占用校验与提示）。 */
    List<Long> findIdsByRoleId(long roleId);
}
