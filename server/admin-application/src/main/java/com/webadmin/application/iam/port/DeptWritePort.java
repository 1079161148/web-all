package com.webadmin.application.iam.port;

import java.util.Optional;

/**
 * 部门写端口。
 *
 * <h3>为什么部门需要"写端口"而用户/角色不需要</h3>
 * 用户与角色有完整的领域聚合，写操作走 {@code Repository}（加载聚合 → 调用业务方法 → 保存）。
 * 而部门是 L2 支撑域，没有聚合、只有一张表与物化路径 ——
 * 但它的写操作<b>仍然需要 SQL 表达</b>（级联更新子树的 ancestors），
 * 而 SQL 是基础设施细节。
 *
 * <p>因此这里定义"应用层需要什么写能力"，由 infrastructure 用 Mapper 实现。
 * 划清的点是：<b>应用层算"新路径应该是什么"，基础设施负责"怎么高效地写下去"。</b>
 *
 * <h3>关于 {@link #rebaseSubtree}</h3>
 * 移动部门时，整棵子树的 ancestors 都要改。逐个节点 update 在深层级下会产生
 * 大量往返，因此用一个批量 SQL 完成（前缀替换）。
 * 这个能力<b>必须由基础设施提供</b> —— 应用层不该知道"前缀替换"这种实现手段。
 */
public interface DeptWritePort {

    /** 写操作需要的最小部门视图（不是完整实体，因为它只服务于路径计算与占用校验）。 */
    record DeptRow(Long id, Long parentId, String ancestors, Integer sort) {
    }

    Optional<DeptRow> findDept(long deptId);

    /** 新增部门，返回新部门 ID。 */
    Long insertDept(long tenantId, long parentId, String ancestors, String deptName,
                    int sort, Long leaderUserId, String phone, String email,
                    String status, String remark);

    void updateDept(long deptId, long parentId, String ancestors, String deptName,
                    int sort, Long leaderUserId, String phone, String email,
                    String status, String remark);

    /**
     * 把子树中所有以 {@code oldPath} 开头的 ancestors 改写为 {@code newPath} 前缀。
     *
     * @return 受影响的节点数
     */
    int rebaseSubtree(long tenantId, String oldPath, String newPath);

    boolean hasChildren(long deptId);

    /** 部门下的用户数。返回数量而非布尔，因为提示文案需要具体人数。 */
    long countUsers(long deptId);

    void deleteDept(long deptId);
}
