package com.webadmin.infrastructure.datascope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.application.iam.port.DeptHierarchyCachePort;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.infrastructure.persistence.mapper.DeptMapper;
import com.webadmin.infrastructure.persistence.po.DeptPO;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 部门层级查询：把物化路径展开成 ID 集合。
 *
 * <h3>为什么是在内存里展开，而不是拼子查询</h3>
 * 另一种实现是把部门范围写成 SQL 子查询：
 * <pre>{@code dept_id IN (SELECT id FROM org_dept WHERE ancestors LIKE '0,100,%')}</pre>
 * 看起来更"优雅"，但有两个实际问题：
 * <ol>
 *   <li><b>租户拦截器的行为难以推理</b>：子查询里的表同样会被改写插入 {@code tenant_id} 条件，
 *       而 MyBatis-Plus 对嵌套子查询的处理在不同版本有差异。
 *       一旦改写不符合预期，就是"越权看到别的租户部门"级别的故障</li>
 *   <li><b>无法缓存</b>：每次查询都要执行子查询。而部门树变化极少</li>
 * </ol>
 *
 * <h3>⚠️ 为什么必须缓存：这个类在"每次 SQL 改写"的路径上</h3>
 * 它由 {@link DataScopePermissionHandler} 调用，而拦截器对<b>每条</b>带
 * {@code @DataScope} 的语句都会改写一次 —— 一个分页请求至少两条
 * （{@code count} + {@code select}）。按原来的实现，每条语句都要跑
 * "按主键取 ancestors + 前缀查子孙"两条查询，于是<b>每次翻页 4 次额外查询</b>，
 * 且随列表接口被调用的频率线性增长。
 *
 * <p>现在改为：<b>按租户缓存整棵树</b>（{@link DeptHierarchyCachePort}），
 * 命中时 0 次查询，在内存中展开（部门树是几百级规模，扫描代价可忽略）。
 * 缓存未命中或 Redis 不可用时回源一次"取该租户全部部门"的查询并回填 ——
 * <b>回源是一次查询而不是每次两条</b>，且"怎么匹配"这段逻辑全仓只有下面一处。
 *
 * <h3>为什么匹配逻辑不做两份（SQL 一份、内存一份）</h3>
 * 曾经的形式是 SQL 做匹配。若改成"缓存里存展开结果、未命中时用 SQL 算"，
 * 同样的子树语义就会有两份实现 —— 而它们一旦漂移，表现是
 * "缓存生效时看不到某些数据、缓存过期后又看得见"，属于最难复现的一类问题。
 * 因此这里让缓存只承担"树从哪来"，匹配只有一份内存实现。
 */
@Slf4j
@Component
public class DeptHierarchyLookup {

    private final DeptMapper deptMapper;
    private final DeptHierarchyCachePort cache;

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
     *
     * <p>缓存端口不需要 {@code @Lazy}：它只依赖 Redis 客户端，不碰 MyBatis。
     */
    public DeptHierarchyLookup(@Lazy DeptMapper deptMapper, DeptHierarchyCachePort cache) {
        this.deptMapper = deptMapper;
        this.cache = cache;
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

        Map<Long, String> tree = loadTree();
        String selfAncestors = tree.get(deptId);
        if (selfAncestors == null) {
            // 部门不存在（或已逻辑删除，或该租户没有任何部门）→ 只含自身
            log.debug("部门层级中未找到 deptId={}，数据范围收敛为其自身", deptId);
            return result;
        }

        // 自身作为父级时的路径前缀：ancestors + "," + id
        // 例：部门 200 的 ancestors='0,100'，则其子部门的 ancestors 以 '0,100,200' 开头
        String childPrefix = selfAncestors + "," + deptId;

        for (Map.Entry<Long, String> entry : tree.entrySet()) {
            String ancestors = entry.getValue();
            // ⚠️ 必须同时匹配「恰好等于前缀」与「前缀 + 逗号」两种形态，这里踩过一次真实的坑：
            //      · 直接子部门的 ancestors **恰好等于**前缀（'0,100,200'）
            //      · 更深子孙才是前缀 + ',' + ...（'0,100,200,210'）
            //    只匹配后者会<b>漏掉直接子部门</b> —— 表现为"数据范围选『本部门及以下』时
            //    看不到直属子部门的用户"，而不报任何错（实测：本该 2 条只返回 1 条）。
            //    另一种改法是给 ancestors 统一加尾逗号，但那要改历史数据 + 所有写入路径
            //    （移动部门、初始化），爆炸半径远大于在此处多写一个判断。
            if (ancestors != null
                    && (childPrefix.equals(ancestors) || ancestors.startsWith(childPrefix + ","))) {
                result.add(entry.getKey());
            }
        }

        log.debug("部门子树展开 deptId={} 含自身共 {} 个部门（树规模 {}）",
                deptId, result.size(), tree.size());
        return result;
    }

    /**
     * 单飞锁表（防击穿）：{@code 租户ID → 锁对象}。
     *
     * <h3>它防的是"缓存击穿"</h3>
     * 缓存过期的瞬间，若有 N 个请求并发未命中，朴素实现会让 N 个线程
     * <b>同时</b>回源数据库（同一个 key 被查 N 次、回填 N 次）。
     * 单飞（singleflight）= 同 key 并发只放一个线程回源，
     * 其余在锁上等待后<b>复读缓存</b> —— 数据库只承受 1 次查询。
     * 锁表条目在 finally 移除，不会随租户数泄漏。
     */
    private static final Map<Long, Object> LOAD_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 取当前租户的部门层级映射：{@code 部门ID → ancestors}。
     *
     * <p>取值顺序：Redis →（未命中，<b>单飞</b>）数据库并回填。
     * 无租户上下文时<b>不碰缓存</b>：缓存 key 必须以租户分段，
     * 拿不到租户就退化为直接回源，而不是猜一个租户或用一个全局 key。
     */
    private Map<Long, String> loadTree() {
        long tenantId = TenantContext.get().orElse(0L);
        if (tenantId <= 0) {
            return loadTreeFromDb();
        }
        Map<Long, String> cached = cache.get(tenantId).orElse(null);
        if (cached != null) {
            return cached;
        }
        synchronized (LOAD_LOCKS.computeIfAbsent(tenantId, key -> new Object())) {
            try {
                // double-check：等锁期间其它线程可能已完成回源并回填
                cached = cache.get(tenantId).orElse(null);
                if (cached != null) {
                    return cached;
                }
                Map<Long, String> loaded = loadTreeFromDb();
                cache.put(tenantId, loaded);
                return loaded;
            } finally {
                LOAD_LOCKS.remove(tenantId);
            }
        }
    }

    /**
     * 从数据库读取该租户的全部部门（{@code id} 与 {@code ancestors} 两列）。
     *
     * <p>不写 {@code tenant_id} 与 {@code del_flag} 条件：
     * 前者由租户拦截器追加、后者由 {@code @TableLogic} 追加。
     * 手写它们会与拦截器重复，且一旦哪天漏写就是静默越权。
     */
    private Map<Long, String> loadTreeFromDb() {
        List<DeptPO> all = deptMapper.selectList(new LambdaQueryWrapper<DeptPO>()
                .select(DeptPO::getId, DeptPO::getAncestors));
        Map<Long, String> tree = new LinkedHashMap<>();
        for (DeptPO po : all) {
            tree.put(po.getId(), po.getAncestors());
        }
        return tree;
    }
}
