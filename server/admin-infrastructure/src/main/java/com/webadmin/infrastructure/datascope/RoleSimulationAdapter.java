package com.webadmin.infrastructure.datascope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.application.iam.port.RoleSimulationPort;
import com.webadmin.domain.iam.model.role.DataScope;
import com.webadmin.infrastructure.persistence.mapper.DeptMapper;
import com.webadmin.infrastructure.persistence.mapper.SurveyTaskMapper;
import com.webadmin.infrastructure.persistence.mapper.UserMapper;
import com.webadmin.infrastructure.persistence.po.DeptPO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据权限模拟的数据访问实现。
 *
 * <h3>统计为何直接调用 Mapper</h3>
 * 因为过滤条件<b>不在</b>本类里，而在 MyBatis 拦截器里
 * （拦截器读 {@code RoleSimulationContext} 中的模拟主体）。
 * 本类只负责"发起与列表接口同一个查询" ——
 * 条件由同一条代码路径产生，预览与实际因此不可能不一致。
 *
 * <h3>新增资源时的维护点</h3>
 * 每接入一个带 {@code @DataScope} 的列表接口，就应在
 * {@link #countVisibleResources()} 里补一行。清单短了不会报错，
 * 只是预览少显示一项 —— 因此这里刻意把"资源清单"集中在一个方法里，
 * 让它一眼可见、便于对照 Mapper 里的注解。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleSimulationAdapter implements RoleSimulationPort {

    private final UserMapper userMapper;
    private final SurveyTaskMapper surveyTaskMapper;
    private final DeptMapper deptMapper;
    private final DeptHierarchyLookup deptHierarchyLookup;

    @Override
    public List<ResourceVisibleCount> countVisibleResources() {
        // 顺序即前端展示顺序：把"人"放第一位（最直观），其余按业务重要性排列
        return List.of(
                new ResourceVisibleCount("iam_user", "用户", userMapper.countVisibleUsers()),
                new ResourceVisibleCount("srvy_task", "调研任务", surveyTaskMapper.countVisibleTasks()));
    }

    @Override
    public List<DeptBrief> effectiveDepts(DataScope scope, Long deptId, Set<Long> customDeptIds) {
        Set<Long> deptIds = switch (scope == null ? DataScope.SELF : scope) {
            // ALL 与 SELF 不由部门集合表达范围：
            //   ALL  → 不限部门（前端给"全部数据"文案）
            //   SELF → 不限部门，但只算本人创建的（靠 create_by 条件）
            // 这里返回空集合是**语义正确**的，不是"没查到"
            case ALL, SELF -> Set.of();
            case DEPT -> deptId == null ? Set.of() : Set.of(deptId);
            // 展开"本部门 + 全部后代"：与运行时同一个 DeptHierarchyLookup，
            // 因此预览里的部门集合与实际过滤用的集合必然一致（同样命中缓存）
            case DEPT_AND_CHILD -> deptId == null
                    ? Set.of()
                    : deptHierarchyLookup.selfAndDescendants(deptId);
            case CUSTOM -> customDeptIds == null ? Set.of() : new LinkedHashSet<>(customDeptIds);
        };

        if (deptIds.isEmpty()) {
            return List.of();
        }

        List<DeptPO> depts = deptMapper.selectList(new LambdaQueryWrapper<DeptPO>()
                .select(DeptPO::getId, DeptPO::getDeptName)
                .in(DeptPO::getId, deptIds));

        Map<Long, String> nameById = depts.stream()
                .collect(Collectors.toMap(DeptPO::getId, DeptPO::getDeptName, (a, b) -> a));

        List<DeptBrief> result = new ArrayList<>(deptIds.size());
        deptIds.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(id -> result.add(new DeptBrief(id, nameById.getOrDefault(id, "（已删除）"))));

        // 部门在 ID 集合里、但查不到名称 → 说明该部门已被删除，
        // 而角色的自定义范围 / 用户的部门仍指向它。这属于需要清理的数据问题，
        // 因此记一条 warn 让它在日志里可见（界面上已用"（已删除）"标注）
        if (nameById.size() < deptIds.size()) {
            log.warn("数据范围模拟中发现引用已不存在部门：期望 {} 个，实际查到 {} 个。deptIds={}",
                    deptIds.size(), nameById.size(), deptIds);
        }
        return result;
    }
}
