package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.webadmin.application.iam.port.DeptWritePort;
import com.webadmin.infrastructure.persistence.mapper.DeptMapper;
import com.webadmin.infrastructure.persistence.mapper.UserMapper;
import com.webadmin.infrastructure.persistence.po.DeptPO;
import com.webadmin.infrastructure.persistence.po.UserPO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/** 部门写端口实现。 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DeptWritePortImpl implements DeptWritePort {

    private final DeptMapper deptMapper;
    private final UserMapper userMapper;

    @Override
    public Optional<DeptRow> findDept(long deptId) {
        DeptPO po = deptMapper.selectById(deptId);
        return po == null ? Optional.empty()
                : Optional.of(new DeptRow(po.getId(), po.getParentId(),
                        po.getAncestors(), po.getSort()));
    }

    @Override
    public Long insertDept(long tenantId, long parentId, String ancestors, String deptName,
                           int sort, Long leaderUserId, String phone, String email,
                           String status, String remark) {
        DeptPO po = new DeptPO();
        // 主键由 IdWorker 生成后显式写入（IdType.INPUT），
        // 多租户与将来分库都需要全局有序 ID，自增无法满足
        po.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        po.setParentId(parentId);
        po.setAncestors(ancestors);
        po.setDeptName(deptName);
        po.setSort(sort);
        po.setLeaderUserId(leaderUserId);
        po.setPhone(phone);
        po.setEmail(email);
        po.setStatus(status == null ? "ACTIVE" : status);
        po.setRemark(remark);
        // tenant_id 不在此设置：租户拦截器会在 INSERT 时自动补上，
        // 这样就不存在"应用层忘了填租户列"的可能
        deptMapper.insert(po);
        return po.getId();
    }

    @Override
    public void updateDept(long deptId, long parentId, String ancestors, String deptName,
                           int sort, Long leaderUserId, String phone, String email,
                           String status, String remark) {
        DeptPO po = new DeptPO();
        po.setId(deptId);
        po.setParentId(parentId);
        po.setAncestors(ancestors);
        po.setDeptName(deptName);
        po.setSort(sort);
        po.setLeaderUserId(leaderUserId);
        po.setPhone(phone);
        po.setEmail(email);
        po.setStatus(status);
        po.setRemark(remark);
        deptMapper.updateById(po);
    }

    /**
     * 级联改写子树的 ancestors。
     *
     * <h3>为什么用 SQL 表达式而不是逐个节点更新</h3>
     * 深层级部门（如 5 层）移动时，子节点可能有几十上百个。
     * 逐个 {@code updateById} 会产生同数量的往返，而这是一次
     * 用户可见的"保存"操作 —— 延迟会直接体现在界面上。
     * 一条 SQL 完成是这里唯一的合理选择。
     *
     * <h3>关于 SQL 字符串拼接的安全性</h3>
     * {@code oldPath} / {@code newPath} 由 {@code DeptAppService} 用
     * <b>纯数字 ID</b> 拼成（形如 {@code 0,100,205}），
     * 不含任何用户可控字符串 —— 部门名等文本字段走的是参数化绑定。
     * 因此这里拼进 SQL 不存在注入面。
     * <b>但正因如此，这个方法绝不能对外开放"任意前缀"。</b>
     */
    @Override
    public int rebaseSubtree(long tenantId, String oldPath, String newPath) {
        // 子树的 path 形如 oldPath + ",..."，而自身那条是自增时写的、同样以 oldPath 开头，
        // 因此统一按前缀匹配即可（含自身，但自身已在上一步单独更新过，
        // 由于路径一致，重复更新是无害的幂等操作）
        UpdateWrapper<DeptPO> wrapper = new UpdateWrapper<DeptPO>()
                .setSql("ancestors = CONCAT('" + newPath + "', SUBSTRING(ancestors, "
                        + (oldPath.length() + 1) + "))")
                .likeRight("ancestors", oldPath + ",");
        return deptMapper.update(null, wrapper);
    }

    @Override
    public boolean hasChildren(long deptId) {
        return deptMapper.exists(new LambdaQueryWrapper<DeptPO>()
                .eq(DeptPO::getParentId, deptId));
    }

    @Override
    public long countUsers(long deptId) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserPO>()
                .eq(UserPO::getDeptId, deptId));
        return count == null ? 0L : count;
    }

    @Override
    public void deleteDept(long deptId) {
        deptMapper.deleteById(deptId);
    }
}
