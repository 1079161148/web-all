package com.webadmin.infrastructure.datascope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.infrastructure.persistence.mapper.DeptMapper;
import com.webadmin.infrastructure.persistence.po.DeptPO;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 部门层级查询：把物化路径展开成 ID 集合。
 *
 * <h3>为什么返回 ID 集合而不是拼子查询</h3>
 * 另一种实现是把部门范围写成 SQL 子查询：
 * <pre>{@code dept_id IN (SELECT id FROM org_dept WHERE ancestors LIKE '0,100,%')}</pre>
 * 看起来更"优雅"，但有两个实际问题：
 * <ol>
 *   <li><b>租户拦截器的行为难以推理</b>：子查询里的表同样会被改写插入 {@code tenant_id} 条件，
 *       而 MyBatis-Plus 对嵌套子查询的处理在不同版本有差异。
 *       一旦改写不符合预期，就是"越权看到别的租户部门"级别的故障</li>
 *   <li><b>无法缓存</b>：每次查询都要执行子查询。而部门树变化极少，
 *       ID 集合是天然的缓存对象</li>
 * </ol>
 * 用两条走索引的简单查询换掉一个复杂子查询，是正确的取舍。
 *
 * <h3>查询代价</h3>
 * 第 1 条按主键取 {@code ancestors}（唯一索引，1 行）；
 * 第 2 条按 {@code (tenant_id, ancestors)} 前缀匹配（有索引）。
 * 两者都是毫秒级，且不随部门总量增长而变慢。
 */
@Slf4j
@Component
public class DeptHierarchyLookup {

    private final DeptMapper deptMapper;

    /**
     * {@code DeptMapper} 必须惰性注入。
     *
     * <p>本类虽只在数据权限改写时被调用，但它是一个 {@code @Component} 单例，
     * 会被 Spring 在启动期<b>提前实例化</b>。若此时急切注入 {@code DeptMapper}，
     * 就会强制触发 {@code sqlSessionFactory} 的创建 ——
     * 而 {@code sqlSessionFactory} 此刻正在等待拦截器链构建完成，
     * 于是再次形成环（与 {@code DataScopePermissionHandler} 是同一个环的不同入口）。
     *
     * <p>这类"同一个循环依赖有多个入口"的情况很典型：只修一处，
     * 应用会在换一个 Bean 加载顺序时再次启动失败，
     * 且两次的报错位置完全不同。<b>凡是能从 MyBatis 拦截器到达的 Bean，
     * 都应默认按"必须在查询期才可用"来设计。</b>
     */
    public DeptHierarchyLookup(@Lazy DeptMapper deptMapper) {
        this.deptMapper = deptMapper;
    }

    /**
     * 查询某部门的全部后代 ID（<b>包含自身</b>）。
     *
     * @param deptId 部门 ID，为 null 时返回空集合
     * @return 部门 ID 集合；查不到该部门时返回只含自身的集合
     */
    public Set<Long> selfAndDescendants(Long deptId) {
        Set<Long> result = new LinkedHashSet<>();
        if (deptId == null || deptId <= 0) {
            return result;
        }
        // 无论能否查到层级，自身一定在范围内 —— 先加入，
        // 这样即使 ancestors 数据异常也不会"连自己的部门都看不到"
        result.add(deptId);

        DeptPO self = deptMapper.selectById(deptId);
        if (self == null) {
            return result;
        }

        // 自身作为父级时的前缀：ancestors + "," + id
        // 例：部门 205 的 ancestors='0,100'，则其子树前缀为 '0,100,205,'
        String childPrefix = self.getAncestors() + "," + self.getId() + ",";

        List<DeptPO> descendants = deptMapper.selectList(
                new LambdaQueryWrapper<DeptPO>()
                        .select(DeptPO::getId)
                        // likeRight 生成 `ancestors LIKE '0,100,205,%'`（右侧通配，可走索引）；
                        // 若用 like 生成 `%...%` 则索引失效 —— 这是物化路径方案能否兑现性能的关键
                        .likeRight(DeptPO::getAncestors, childPrefix));
        descendants.forEach(po -> result.add(po.getId()));

        log.debug("部门子树展开 deptId={} 含自身共 {} 个部门", deptId, result.size());
        return result;
    }
}
