package com.webadmin.domain.iam.repository;

import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户仓储（领域层定义的端口，实现在 infrastructure）。
 *
 * <h3>接口设计上的几个刻意选择</h3>
 * <ul>
 *   <li><b>返回 {@code Optional} 而非 null</b>：强制调用方处理"不存在"这一分支。
 *       用户不存在是最常见的正常路径（查错 ID、已被删），不该靠 NPE 发现</li>
 *   <li><b>不提供 {@code findAll()} / {@code count()}</b>：仓储不是查询服务。
 *       列表、分页、统计走 {@code UserQueryPort}（CQRS 读侧），
 *       这样写侧接口不会被"顺便加个筛选条件"侵蚀成万能 DAO</li>
 *   <li><b>{@link #existsByUsername} 单独存在</b>：唯一性校验是高频且在事务内
 *       必须尽早失败的操作，用 {@code count > 0} 比查完整对象再判空更贴切语义，
 *       也更容易被优化成 {@code SELECT 1 ... LIMIT 1}</li>
 *   <li><b>{@link #findByRoleId} 放在这里</b>：角色权限变更后需要失效所有关联用户的
 *       权限缓存，这个"按角色反查用户"是权限闭环的必需能力。
 *       它返回的是<b>用户 ID 集合</b>而非用户对象 —— 失效缓存只需要 ID，
 *       加载完整聚合纯属浪费</li>
 * </ul>
 */
public interface UserRepository {

    Optional<User> findById(UserId id);

    Optional<User> findByUsername(Username username);

    /** 用户名是否已存在（用于创建前的快速校验；最终唯一性由数据库唯一索引保证）。 */
    boolean existsByUsername(Username username);

    /** 按角色反查用户 ID（权限变更后失效缓存用）。 */
    Set<UserId> findIdsByRoleId(RoleId roleId);

    /**
     * 只取用户所属部门 ID。
     *
     * <p>单独提供这个方法而不是让调用方用 {@link #findById}：
     * 数据权限解析（每条查询都要用）只需要一个 {@code dept_id}，
     * 而 {@link #findById} 会连带把角色关联也查出来 ——
     * <b>在热路径上加载用不到的数据，是"看起来没问题、压测时才发现"的典型浪费。</b>
     *
     * @return 部门 ID；用户不存在或未分配部门时返回空
     */
    Optional<Long> findDeptId(UserId id);

    /** 批量按 ID 加载（避免调用方在循环里逐个查询造成 N+1）。 */
    List<User> findAllByIds(Collection<UserId> ids);

    /**
     * 保存（新建或更新）。
     *
     * <p>用统一的 {@code save} 而不是 {@code insert}/{@code update} 两个方法：
     * 调用方（应用服务）通常不关心底层是插入还是更新，它只知道"我改了聚合，请落库"。
     * 由实现根据 ID 是否存在来分支判断，可以避免"忘了调 update"这类错误。
     */
    void save(User user);

    /** 逻辑删除。 */
    void delete(UserId id);
}
