package com.webadmin.application.iam;

import com.webadmin.application.iam.dto.RoleSimulationView;
import com.webadmin.application.iam.port.RoleSimulationPort;
import com.webadmin.application.iam.security.RoleSimulationContext;
import com.webadmin.application.iam.security.SimulatedSubject;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.role.DataScope;
import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.model.user.User;
import com.webadmin.domain.iam.model.user.Username;
import com.webadmin.domain.iam.repository.RoleRepository;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.DeptId;
import com.webadmin.domain.shared.RoleId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据权限模拟（"以某角色预览可见数据"）。
 *
 * <h3>这个功能真正难的地方不是算范围，而是"让预览可信"</h3>
 * 最省事的实现是：拿角色声明的数据范围，在应用层自己拼一套 WHERE 去统计。
 * 那样写出来的预览<b>迟早与实际不一致</b>：运行时条件由 MyBatis 拦截器生成
 * （涉及别名、空集合收敛、部门树展开、超管豁免等细节），
 * 任何一处细微差别都表现为"预览说 8 条，打开列表 12 条" ——
 * 而这个功能存在的全部意义就是让人相信预览结果。
 *
 * <p>因此这里的做法是：<b>把"当前主体"临时换成被模拟的主体，
 * 然后调用真实的列表统计查询</b>（见 {@code RoleSimulationContext}）。
 * 条件由同一条代码路径产生，一致性是结构上的，而不是靠人去同步两份逻辑。
 *
 * <h3>为什么返回的是"角色 + 用户"而不是只给角色</h3>
 * {@code SELF} / {@code DEPT} / {@code DEPT_AND_CHILD} 都依赖"人在哪"，
 * 只给角色无法回答"能看到什么"。这一点在 {@link SimulatedSubject} 里有详细说明。
 *
 * <h3>为什么拒绝模拟超管角色</h3>
 * 超管的数据范围被解析为 {@code ALL}，模拟它必然得到"全部数据"，
 * 没有信息量，却容易让人误以为"这个角色能看到全部"，属于误导性输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleSimulationAppService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final RoleSimulationPort roleSimulationPort;

    /**
     * 模拟"账号为 {@code targetUsername} 的用户仅拥有角色 {@code roleId} 的数据范围"时可见的数据。
     *
     * <h3>⚠️ 为什么入参是"账号"而不是"用户 ID"</h3>
     * 主键是雪花 ID（19 位，超过 {@code 2^53}），而浏览器端的 JavaScript 数字
     * <b>无法精确表示</b>它：{@code 2100960296716267522} 传到后端会变成 {@code 2100960296716267500}，
     * 于是后端只能回答"用户不存在"。这个精度问题与本功能无关，
     * 但本功能刚好是最先暴露它的地方（它需要把一个<b>列表里拿到的</b>用户 ID 再传回来）。
     *
     * <p>用账号（字符串）入参可以从根上避开它，顺带让接口<b>可读、可手工调用</b>：
     * 出问题时把 URL 贴进浏览器或日志里就能复现，而不必再去查"这个 ID 是谁"。
     * 账号在租户内唯一（{@code uk_iam_user_tenant_username}），因此表达力等价。
     *
     * <p>只读：不写任何业务数据；模拟上下文仅在这次方法调用内生效。
     */
    @Transactional(readOnly = true)
    public RoleSimulationView simulate(long roleId, String targetUsername) {
        long tenantId = TenantContext.require();

        Role role = roleRepository.findById(RoleId.of(roleId))
                .orElseThrow(() -> new BizException(IamErrorCode.ROLE_NOT_FOUND,
                        "角色不存在或已被删除（ID=" + roleId + "）"));
        if (!role.isUsable()) {
            // 停用角色不参与鉴权（见 PermissionResolver），模拟它得到的范围与实际不符，
            // 因此明确拒绝而不是给出一个"看起来正常"的结果
            throw new BizException(IamErrorCode.ROLE_ILLEGAL_STATE,
                    "角色「" + role.roleName() + "」已停用。停用角色不参与鉴权，无法预览其数据范围");
        }
        if (role.isSuperAdmin()) {
            throw new BizException(IamErrorCode.ROLE_ILLEGAL_STATE,
                    "超级管理员不受数据范围限制（可见全部数据），无需预览");
        }

        User user = userRepository.findByUsername(Username.of(targetUsername))
                .orElseThrow(() -> new BizException(IamErrorCode.USER_NOT_FOUND,
                        "被模拟的用户不存在（账号=" + targetUsername + "）"));
        long targetUserId = user.id().value();
        Long deptId = userRepository.findDeptId(user.id()).orElse(null);
        DataScope scope = role.dataScope();

        if (deptId == null && (scope == DataScope.DEPT || scope == DataScope.DEPT_AND_CHILD)) {
            // 不是错误：运行时对这种组合会降级为 SELF（见 DataScopeConditionBuilder）。
            // 但这是配置层面值得注意的信号，因此记录下来，便于排查"为什么这个角色只看得到自己的数据"
            log.info("被模拟用户未分配部门，范围 {} 将按运行时语义降级为「仅本人」。roleId={} userId={}",
                    scope, roleId, targetUserId);
        }

        Set<Long> customDeptIds = toDeptIds(role);

        SimulatedSubject subject = new SimulatedSubject(targetUserId, deptId, scope, customDeptIds);
        // ⚠️ 统计必须在这个块内完成：块外模拟已结束，拦截器会按"调用者本人"过滤
        List<RoleSimulationPort.ResourceVisibleCount> resources =
                RoleSimulationContext.runWith(subject, roleSimulationPort::countVisibleResources);

        List<RoleSimulationPort.DeptBrief> depts =
                roleSimulationPort.effectiveDepts(scope, deptId, customDeptIds);

        log.info("数据权限模拟 tenantId={} roleId={} roleKey={} 被模拟用户={} 范围={} 可见部门数={} 资源条数={}",
                tenantId, roleId, role.roleKey().value(), targetUserId, scope, depts.size(),
                resources.stream()
                        .map(r -> r.label() + "=" + r.visible())
                        .toList());

        return new RoleSimulationView(
                roleId,
                role.roleKey().value(),
                role.roleName(),
                targetUserId,
                user.username().value(),
                user.nickname(),
                scope.name(),
                deptId,
                depts.isEmpty() ? null : depts.get(0).deptName(),
                depts,
                resources);
    }

    private Set<Long> toDeptIds(Role role) {
        Set<Long> result = new LinkedHashSet<>();
        for (DeptId deptId : role.deptIds()) {
            result.add(deptId.value());
        }
        return result;
    }
}
