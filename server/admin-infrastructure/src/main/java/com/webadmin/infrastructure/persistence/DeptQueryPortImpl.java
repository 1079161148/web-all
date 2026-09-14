package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.application.iam.dto.DeptDTO;
import com.webadmin.application.iam.port.DeptQueryPort;
import com.webadmin.infrastructure.persistence.mapper.DeptMapper;
import com.webadmin.infrastructure.persistence.po.DeptPO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 部门读侧实现。 */
@Repository
@RequiredArgsConstructor
public class DeptQueryPortImpl implements DeptQueryPort {

    private final DeptMapper deptMapper;

    @Override
    public List<DeptDTO> listAll() {
        return deptMapper.selectList(new LambdaQueryWrapper<DeptPO>()
                        .orderByAsc(DeptPO::getParentId)
                        .orderByAsc(DeptPO::getSort)
                        .orderByAsc(DeptPO::getId))
                .stream()
                .map(DeptQueryPortImpl::toDTO)
                .toList();
    }

    @Override
    public Optional<DeptDTO> findById(long deptId) {
        return Optional.ofNullable(deptMapper.selectById(deptId))
                .map(DeptQueryPortImpl::toDTO);
    }

    private static DeptDTO toDTO(DeptPO po) {
        return new DeptDTO(
                po.getId(), po.getTenantId(), po.getParentId(), po.getAncestors(),
                po.getDeptName(), po.getSort(), po.getLeaderUserId(),
                po.getPhone(), po.getEmail(), po.getStatus(), po.getRemark(),
                po.getCreateTime());
    }
}
