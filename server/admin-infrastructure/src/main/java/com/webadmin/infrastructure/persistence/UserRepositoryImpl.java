package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.UserId;
import com.webadmin.infrastructure.persistence.converter.UserConverter;
import com.webadmin.infrastructure.persistence.mapper.UserMapper;
import com.webadmin.infrastructure.persistence.mapper.UserRoleMapper;
import com.webadmin.infrastructure.persistence.po.UserPO;
import com.webadmin.infrastructure.persistence.po.UserRolePO;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * 用户仓储实现。
 *
 * <h3>关于租户条件</h3>
 * 这里<b>一处都没有手写 {@code tenant_id}</b> —— 全部由
 * {@code TenantLineInnerInterceptor} 自动追加。这正是多租户设计的价值：
 * 仓储代码看起来像单租户，隔离由基础设施统一保证。
 *
 * <p>代价是：一旦某处需要"跨租户"（比如平台运营查所有租户的用户），
 * 就必须显式声明例外，而不是悄悄地少写一个条件。这个方向是正确的 ——
 * <b>默认隔离，例外显式</b>。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserConverter userConverter;
    private final Clock clock;

    @Override
    public Optional<User> findById(UserId id) {
        UserPO po = userMapper.selectById(id.value());
        if (po == null) {
            return Optional.empty();
        }
        return Optional.of(userConverter.toDomain(po, loadRoleIds(id.value()), clock));
    }

    @Override
    public Optional<User> findByUsername(Username username) {
        UserPO po = userMapper.selectOne(new LambdaQueryWrapper<UserPO>()
                .eq(UserPO::getUsername, username.value())
                // 必须显式 limit 1：若历史数据里存在重复用户名（唯一索引被绕过、
                // 或 del_flag 语义变更期间的脏数据），selectOne 会抛 TooManyResultsException，
                // 表现为"登录接口 500"而不是"提示用户名异常"，排查方向会被带偏。
                .last("LIMIT 1"));
        if (po == null) {
            return Optional.empty();
        }
        return Optional.of(userConverter.toDomain(po, loadRoleIds(po.getId()), clock));
    }

    @Override
    public boolean existsByUsername(Username username) {
        return userMapper.exists(new LambdaQueryWrapper<UserPO>()
                .eq(UserPO::getUsername, username.value()));
    }

    @Override
    public Optional<Long> findDeptId(UserId id) {
        // 只 select dept_id 一列：这是数据权限解析的热路径，
        // 拉回整行（含 password、avatar 等大字段）纯属浪费带宽与内存
        UserPO po = userMapper.selectOne(new LambdaQueryWrapper<UserPO>()
                .select(UserPO::getDeptId)
                .eq(UserPO::getId, id.value()));
        return po == null ? Optional.empty() : Optional.ofNullable(po.getDeptId());
    }

    @Override
    public Set<UserId> findIdsByRoleId(RoleId roleId) {
        List<UserRolePO> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRolePO>().eq(UserRolePO::getRoleId, roleId.value()));
        return relations.stream()
                .map(relation -> UserId.of(relation.getUserId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public List<User> findAllByIds(Collection<UserId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> idList = ids.stream().map(UserId::value).toList();
        List<UserPO> pos = userMapper.selectByIds(idList);
        if (pos.isEmpty()) {
            return List.of();
        }
        // 一次性把所有用户的角色查出来后在内存分组，避免 N+1。
        // 鉴权会频繁走到这里（缓存未命中时），N+1 的代价会被放大成数据库压力。
        Map<Long, Set<Long>> roleIdsByUser = loadRoleIdsGroupedByUser(idList);
        return pos.stream()
                .map(po -> userConverter.toDomain(
                        po, roleIdsByUser.getOrDefault(po.getId(), Set.of()), clock))
                .toList();
    }

    @Override
    public void save(User user) {
        UserPO existing = userMapper.selectById(user.id().value());
        if (existing == null) {
            UserPO po = userConverter.toPO(user);
            userMapper.insert(po);
        } else {
            userConverter.mergeIntoPO(user, existing);
            int affected = userMapper.updateById(existing);
            if (affected == 0) {
                log.warn("用户并发修改冲突: id={}", user.id().value());
                throw new BizException(CommonErrorCode.CONCURRENT_MODIFICATION,
                        "用户信息已被他人修改，请刷新后重试");
            }
        }
        syncRoles(user);
    }

    @Override
    public void delete(UserId id) {
        // 逻辑删除由 @TableLogic 处理（del_flag 写入本行 id），
        // 但用户-角色关联必须显式清理：它是物理表，没有逻辑删除列。
        // 若不清理，被删用户重新创建时会带着"历史角色"复活 —— 一个隐蔽的越权入口。
        userRoleMapper.delete(new LambdaQueryWrapper<UserRolePO>()
                .eq(UserRolePO::getUserId, id.value()));
        userMapper.deleteById(id.value());
    }

    /**
     * 同步用户-角色关联（全量覆盖）。
     *
     * <p>用"先删后插"而不是差分计算：关联行数很少（通常 < 10），
     * 差分逻辑的复杂度与其带来的收益不成比例，而且容易出现"少删一条"这种静默错误。
     * 注意这里没有额外包事务 —— 仓储方法是应用层事务的一部分，嵌套事务没有意义。
     */
    private void syncRoles(User user) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRolePO>()
                .eq(UserRolePO::getUserId, user.id().value()));
        for (RoleId roleId : user.roleIds()) {
            UserRolePO relation = new UserRolePO();
            // tenant_id 不在此处设置：租户拦截器会在 INSERT 时自动补上，
            // 这样就不存在"应用层忘了填租户列"的可能。
            relation.setUserId(user.id().value());
            relation.setRoleId(roleId.value());
            userRoleMapper.insert(relation);
        }
    }

    private Set<Long> loadRoleIds(long userId) {
        List<UserRolePO> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRolePO>().eq(UserRolePO::getUserId, userId));
        return relations.stream()
                .map(UserRolePO::getRoleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<Long, Set<Long>> loadRoleIdsGroupedByUser(List<Long> userIds) {
        List<UserRolePO> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRolePO>().in(UserRolePO::getUserId, userIds));
        return relations.stream().collect(Collectors.groupingBy(
                UserRolePO::getUserId,
                Collectors.mapping(UserRolePO::getRoleId,
                        Collectors.toCollection(LinkedHashSet::new))));
    }
}
