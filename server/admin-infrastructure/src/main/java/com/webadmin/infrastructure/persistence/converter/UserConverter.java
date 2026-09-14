package com.webadmin.infrastructure.persistence.converter;

import com.webadmin.domain.iam.model.user.PasswordHash;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.UserStatus;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.domain.shared.UserId;
import com.webadmin.infrastructure.persistence.po.UserPO;
import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 用户聚合 ↔ PO 转换。
 *
 * <p>手写而非 MapStruct，理由与 {@code TenantConverter} 相同：
 * 聚合重建需要调用 {@code reconstitute}（不产生事件）、把扁平列还原成值对象、
 * 并把角色 ID 集合一并装配 —— 这不是机械映射。MapStruct 在这里会退化成
 * 满屏 {@code expression = "java(...)"}，可读性反而更差。
 */
@Component
public class UserConverter {

    public UserPO toPO(User user) {
        UserPO po = new UserPO();
        po.setId(user.id().value());
        po.setTenantId(user.tenantId().value());
        po.setDeptId(user.deptId());
        po.setUsername(user.username().value());
        po.setNickname(user.nickname());
        po.setPassword(user.password().value());
        po.setEmail(user.email());
        po.setPhone(user.phone());
        po.setSex(user.sex());
        po.setAvatar(user.avatar());
        po.setStatus(user.status());
        po.setLoginIp(user.loginIp());
        po.setLoginTime(user.loginTime());
        po.setFailCount(user.failCount());
        po.setLockUntil(user.lockUntil());
        return po;
    }

    /**
     * PO → 聚合。
     *
     * @param roleIds 角色 ID 集合（由仓储单独查出后传入，避免在转换器里访问数据库）
     */
    public User toDomain(UserPO po, Set<Long> roleIds, Clock clock) {
        if (po == null) {
            return null;
        }
        Set<RoleId> roles = roleIds == null ? Set.of()
                : roleIds.stream().map(RoleId::of).collect(Collectors.toSet());
        return User.reconstitute(
                UserId.of(po.getId()),
                TenantId.ofPersisted(po.getTenantId() == null ? 0L : po.getTenantId()),
                Username.ofPersisted(po.getUsername()),
                PasswordHash.ofPersisted(po.getPassword()),
                po.getNickname(),
                po.getEmail(),
                po.getPhone(),
                po.getDeptId(),
                po.getSex(),
                po.getAvatar(),
                po.getStatus() == null ? UserStatus.ACTIVE : po.getStatus(),
                po.getLoginIp(),
                po.getLoginTime(),
                po.getFailCount() == null ? 0 : po.getFailCount(),
                po.getLockUntil(),
                roles,
                clock);
    }

    /**
     * 把聚合的可变状态回填到已加载的 PO 上。
     *
     * <p>用于更新场景：<b>必须复用已加载的 PO</b> 而不是新建对象，
     * 否则会丢掉 {@code version}（乐观锁失效）、{@code create_time} / {@code create_by}
     * 与 {@code del_flag}，造成"更新一次就把创建信息清空"这类数据损坏。
     */
    public void mergeIntoPO(User user, UserPO po) {
        po.setDeptId(user.deptId());
        po.setNickname(user.nickname());
        po.setPassword(user.password().value());
        po.setEmail(user.email());
        po.setPhone(user.phone());
        po.setSex(user.sex());
        po.setAvatar(user.avatar());
        po.setStatus(user.status());
        po.setLoginIp(user.loginIp());
        po.setLoginTime(user.loginTime());
        po.setFailCount(user.failCount());
        po.setLockUntil(user.lockUntil());
    }
}
