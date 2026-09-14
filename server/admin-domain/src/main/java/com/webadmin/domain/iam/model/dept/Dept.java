package com.webadmin.domain.iam.model.dept;

import com.webadmin.domain.shared.DeptId;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门（<b>L2 支撑域模型</b>）。
 *
 * <h3>核心是 ancestors 物化路径</h3>
 * 每个部门保存从根到父级的完整 ID 路径（如 {@code "0,100,205"}）。
 * 这样"本部门及以下"的数据权限只需：
 * <pre>{@code ancestors LIKE '0,100,205,%' OR id = 205}</pre>
 * 一条走索引的 SQL，无需递归。
 *
 * <p><b>代价是移动部门要级联更新子树</b>。这里提供
 * {@link #reparentTo} 计算新路径，但级联更新由仓储批量执行 ——
 * 领域层负责"算出正确的新值"，持久化层负责"高效地写下去"。
 *
 * <h3>为什么不建物理外键</h3>
 * 设计文档 §9.3 规定不建物理外键。部门删除时的"是否有子部门/是否有用户"
 * 由应用层校验，因为这类校验往往还需要给出可读的提示文案（"该部门下还有 3 名员工"），
 * 那是外键约束表达不了的。
 */
public class Dept {

    private final DeptId id;
    private final DeptId parentId;
    private final String ancestors;
    private final String deptName;
    private final int sort;
    private final Long leaderUserId;
    private final String status;

    private Dept(DeptId id, DeptId parentId, String ancestors, String deptName,
                 int sort, Long leaderUserId, String status) {
        this.id = id;
        this.parentId = parentId;
        this.ancestors = ancestors;
        this.deptName = deptName;
        this.sort = sort;
        this.leaderUserId = leaderUserId;
        this.status = status;
    }

    public static Dept of(DeptId id, DeptId parentId, String ancestors, String deptName,
                          int sort, Long leaderUserId, String status) {
        if (deptName == null || deptName.isBlank()) {
            throw new IllegalArgumentException("部门名称不能为空");
        }
        return new Dept(id, parentId, ancestors, deptName.trim(), sort, leaderUserId,
                status == null ? "ACTIVE" : status);
    }

    /**
     * 计算"把本部门挂到新父级下"时，本部门应有的新 ancestors。
     *
     * @param newParentAncestors 新父级的 ancestors
     * @return 新的 ancestors 值
     */
    public String ancestorsUnder(String newParentAncestors) {
        return newParentAncestors + "," + id.value();
    }

    /** 新子部门的 ancestors（父级 ancestors + 父级 id）。 */
    public String ancestorsForChild() {
        return ancestors + "," + id.value();
    }

    /**
     * 判断某部门是否是本部门的后代（用于阻止"把部门挂到自己的子部门下"）。
     *
     * <p>这类循环引用如果不在写入口拦住，会让部门树出现环，
     * 之后任何一次树遍历都会无限递归 —— 且问题在数据录入很久之后才爆发。
     */
    public boolean isAncestorOf(DeptId candidateId) {
        if (candidateId == null) {
            return false;
        }
        String prefix = ancestors + "," + id.value() + ",";
        String exact = String.valueOf(candidateId.value());
        return ("," + ancestors + ",").contains("," + exact + ",") || prefix.contains("," + exact + ",");
    }

    /** 在内存中组装部门树（规则同菜单树：父不存在时挂根、按 sort 稳定排序）。 */
    public static List<DeptTreeNode> buildTree(List<Dept> flatDepts) {
        java.util.Map<Long, DeptTreeNode> nodes = new java.util.LinkedHashMap<>();
        for (Dept dept : flatDepts) {
            nodes.put(dept.id().value(), new DeptTreeNode(dept));
        }
        List<DeptTreeNode> roots = new ArrayList<>();
        for (DeptTreeNode node : nodes.values()) {
            long parentId = node.dept.parentId().value();
            DeptTreeNode parent = parentId == 0L ? null : nodes.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        sortRecursively(roots);
        return roots;
    }

    private static void sortRecursively(List<DeptTreeNode> nodes) {
        nodes.sort(java.util.Comparator.comparingInt(n -> n.dept.sort()));
        for (DeptTreeNode node : nodes) {
            sortRecursively(node.children);
        }
    }

    public boolean isUsable() {
        return "ACTIVE".equals(status);
    }

    // ---- 访问器 ----
    public DeptId id() {
        return id;
    }

    public DeptId parentId() {
        return parentId;
    }

    public String ancestors() {
        return ancestors;
    }

    public String deptName() {
        return deptName;
    }

    public int sort() {
        return sort;
    }

    public Long leaderUserId() {
        return leaderUserId;
    }

    public String status() {
        return status;
    }

    /** 树节点。 */
    public static final class DeptTreeNode {

        private final Dept dept;
        private final List<DeptTreeNode> children = new ArrayList<>();

        DeptTreeNode(Dept dept) {
            this.dept = dept;
        }

        public Dept dept() {
            return dept;
        }

        public List<DeptTreeNode> children() {
            return List.copyOf(children);
        }
    }
}
