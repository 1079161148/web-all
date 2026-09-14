package com.webadmin.application.iam;

import com.webadmin.application.iam.port.DeptWritePort;
import com.webadmin.application.iam.port.DeptWritePort.DeptRow;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部门应用服务（L2 支撑域，事务脚本）。
 *
 * <h3>本模块真正的复杂点在"物化路径的维护"</h3>
 * 部门的读极其简单（按 ancestors 前缀匹配），但<b>写不简单</b>：
 * <ul>
 *   <li>新增：ancestors = 父级.ancestors + "," + 父级.id</li>
 *   <li>移动：自身的 ancestors 要改，<b>整棵子树的所有节点都要跟着改</b></li>
 *   <li>移动时必须防环：不能把部门移到自己的子孙下面</li>
 * </ul>
 * 这是选择物化路径的代价 —— <b>用写的复杂度换读的性能</b>。
 * 部门树的读远多于写（每次数据权限判定都要读），这个取舍是划算的。
 *
 * <h3>为什么通过 {@link DeptWritePort} 而不是直接注入 Mapper</h3>
 * 应用层不应依赖 infrastructure（ArchUnit 会拦）。部门写操作需要
 * Mapper 与物化路径的 SQL 表达式，属于基础设施细节，
 * 因此定义一个写端口由 infrastructure 实现 ——
 * <b>应用层负责"算出正确的新路径"，基础设施负责"高效地写下去"。</b>
 *
 * <p>⚠️ 这个端口最初被写成了 {@code com.webadmin.infrastructure.spi.DeptWritePort}，
 * 直接编译失败 —— 因为 application 模块根本不依赖 infrastructure。
 * <b>"依赖方向错了会编译不过"正是分层的价值</b>：如果模块之间是循环依赖，
 * 这个错误会一路潜伏到运行期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeptAppService {

    private final DeptWritePort deptWritePort;

    @Transactional
    public Long createDept(Long parentId, String deptName, Integer sort, Long leaderUserId,
                           String phone, String email, String status, String remark) {
        long tenantId = TenantContext.require();
        long effectiveParentId = parentId == null ? 0L : parentId;

        String ancestors = resolveAncestorsForChild(effectiveParentId);
        Long id = deptWritePort.insertDept(tenantId, effectiveParentId, ancestors, deptName,
                sort == null ? 0 : sort, leaderUserId, phone, email, status, remark);
        log.info("创建部门 deptId={} name={} parentId={} ancestors={}",
                id, deptName, effectiveParentId, ancestors);
        return id;
    }

    /**
     * 修改部门（含可能的"移动"）。
     *
     * <p>移动是这里唯一需要小心的操作：必须同时做两件事 ——
     * 改自己的 path，以及<b>级联改整棵子树</b>。只改自己会让子节点的 ancestors
     * 指向一条已经不存在的路径，后果是"本部门及以下"的数据权限
     * <b>查不到任何子部门</b>，而部门树界面上看不出任何异常。
     */
    @Transactional
    public void updateDept(long deptId, Long parentId, String deptName, Integer sort,
                           Long leaderUserId, String phone, String email,
                           String status, String remark) {
        long tenantId = TenantContext.require();
        DeptRow self = deptWritePort.findDept(deptId)
                .orElseThrow(() -> new BizException(IamErrorCode.DEPT_NOT_FOUND, "部门不存在"));

        long newParentId = parentId == null ? self.parentId() : parentId;
        String newAncestors = self.ancestors();

        boolean moved = newParentId != self.parentId();
        if (moved) {
            assertNoCycle(deptId, newParentId);
            newAncestors = resolveAncestorsForChild(newParentId);
        }

        deptWritePort.updateDept(deptId, newParentId, newAncestors, deptName,
                sort == null ? self.sort() : sort, leaderUserId, phone, email, status, remark);

        if (moved) {
            // 级联更新子树：把旧路径前缀整体替换为新路径前缀。
            // 例：205 从 '0,100' 移到 '0,300' 下，
            //     旧路径 '0,100,205' → 新路径 '0,300,205'，
            //     其子节点的 '0,100,205,301' → '0,300,205,301'
            String oldPath = self.ancestors() + "," + deptId;
            String newPath = newAncestors + "," + deptId;
            int affected = deptWritePort.rebaseSubtree(tenantId, oldPath, newPath);
            log.info("移动部门并级联更新子树 deptId={} {} → {} 影响子节点数={}",
                    deptId, oldPath, newPath, affected);
        } else {
            log.info("修改部门 deptId={} name={}", deptId, deptName);
        }
    }

    @Transactional
    public void deleteDept(long deptId) {
        if (deptWritePort.hasChildren(deptId)) {
            throw new BizException(IamErrorCode.DEPT_HAS_CHILDREN,
                    "该部门下还有子部门，请先删除或移出子部门");
        }
        // 提示文案带上具体人数 —— "还有 3 名员工"比"存在关联用户"有用得多，
        // 后者只会让人去猜"到底是谁"。提示的价值在于给出行动依据。
        long userCount = deptWritePort.countUsers(deptId);
        if (userCount > 0) {
            throw new BizException(IamErrorCode.DEPT_HAS_USERS,
                    "该部门下还有 " + userCount + " 名员工，请先调整他们的部门");
        }
        deptWritePort.deleteDept(deptId);
        log.info("删除部门 deptId={}", deptId);
    }

    // ------------------------------------------------------------------

    /** 计算"挂在指定父级下"时子节点应有的 ancestors。 */
    private String resolveAncestorsForChild(long parentId) {
        if (parentId == 0L) {
            return "0";
        }
        DeptRow parent = deptWritePort.findDept(parentId)
                .orElseThrow(() -> new BizException(IamErrorCode.DEPT_NOT_FOUND,
                        "上级部门不存在（ID=" + parentId + "）"));
        return parent.ancestors() + "," + parent.id();
    }

    /**
     * 防止把部门移到自己的子孙下面。
     *
     * <p>若不拦，部门树会出现环 —— 之后任何一次树遍历都会无限递归。
     * 而这个问题在数据录入很久之后才爆发（表现为页面卡死），
     * 与真正的成因（某次移动操作）已经隔了很远。
     *
     * <p>判据：新父级的 ancestors 链里如果包含 deptId，说明新父级是 deptId 的后代。
     */
    private void assertNoCycle(long deptId, long newParentId) {
        if (newParentId == deptId) {
            throw new BizException(IamErrorCode.DEPT_CYCLE_DETECTED, "不能把部门移到自己下面");
        }
        DeptRow newParent = deptWritePort.findDept(newParentId)
                .orElseThrow(() -> new BizException(IamErrorCode.DEPT_NOT_FOUND,
                        "上级部门不存在（ID=" + newParentId + "）"));
        String parentPath = "," + newParent.ancestors() + ",";
        if (parentPath.contains("," + deptId + ",")) {
            throw new BizException(IamErrorCode.DEPT_CYCLE_DETECTED,
                    "不能把部门移动到它自己的子部门下面，否则部门树会形成环");
        }
    }
}
