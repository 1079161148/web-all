package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.platform.dto.DictDataDTO;
import com.webadmin.application.platform.dto.DictTypeDTO;
import com.webadmin.application.platform.port.DictQueryPort;
import com.webadmin.application.platform.query.DictDataPageQuery;
import com.webadmin.application.platform.query.DictTypePageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.infrastructure.persistence.mapper.DictDataMapper;
import com.webadmin.infrastructure.persistence.mapper.DictTypeMapper;
import com.webadmin.infrastructure.persistence.po.DictDataPO;
import com.webadmin.infrastructure.persistence.po.DictTypePO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 字典读侧实现。
 *
 * <h3>⚠️ 本类必须自己写 {@code tenant_id IN (0, ?)}</h3>
 * 字典表在租户拦截器的忽略名单里，因此<b>没有任何自动隔离</b>。
 * 每一处查询都必须显式表达"平台默认 + 本租户覆盖"这个可见范围。
 *
 * <p>为了不把这个容易忘的条件散落到 5 个方法里，统一抽成
 * {@link #platformOrTenantScope()}。<b>一处写错只错一处，也只需要测一处。</b>
 */
@Repository
@RequiredArgsConstructor
public class DictQueryPortImpl implements DictQueryPort {

    private final DictTypeMapper dictTypeMapper;
    private final DictDataMapper dictDataMapper;

    // ==================================================================
    // 字典类型
    // ==================================================================

    @Override
    public PageResult<DictTypeDTO> pageTypes(DictTypePageQuery query) {
        Page<DictTypePO> page = dictTypeMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<DictTypePO>()
                        .in(DictTypePO::getTenantId, platformOrTenantScope())
                        .like(query.getDictName() != null && !query.getDictName().isBlank(),
                                DictTypePO::getDictName, query.getDictName())
                        .like(query.getDictType() != null && !query.getDictType().isBlank(),
                                DictTypePO::getDictType, query.getDictType())
                        .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                                DictTypePO::getStatus, query.getStatus())
                        .orderByAsc(DictTypePO::getDictType));

        return PageResult.of(page.getRecords().stream().map(DictQueryPortImpl::toTypeDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<DictTypeDTO> findTypeById(long id) {
        DictTypePO po = dictTypeMapper.selectOne(new LambdaQueryWrapper<DictTypePO>()
                .eq(DictTypePO::getId, id)
                .in(DictTypePO::getTenantId, platformOrTenantScope()));
        return Optional.ofNullable(po).map(DictQueryPortImpl::toTypeDTO);
    }

    // ==================================================================
    // 字典项
    // ==================================================================

    @Override
    public PageResult<DictDataDTO> pageData(DictDataPageQuery query) {
        Page<DictDataPO> page = dictDataMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<DictDataPO>()
                        .in(DictDataPO::getTenantId, platformOrTenantScope())
                        .eq(query.getDictType() != null && !query.getDictType().isBlank(),
                                DictDataPO::getDictType, query.getDictType())
                        .like(query.getDictLabel() != null && !query.getDictLabel().isBlank(),
                                DictDataPO::getDictLabel, query.getDictLabel())
                        .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                                DictDataPO::getStatus, query.getStatus())
                        .orderByAsc(DictDataPO::getSort));

        return PageResult.of(page.getRecords().stream().map(DictQueryPortImpl::toDataDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public List<DictDataDTO> listByType(String dictType) {
        if (dictType == null || dictType.isBlank()) {
            return List.of();
        }
        List<DictDataPO> rows = dictDataMapper.selectList(new LambdaQueryWrapper<DictDataPO>()
                .in(DictDataPO::getTenantId, platformOrTenantScope())
                .eq(DictDataPO::getDictType, dictType)
                .eq(DictDataPO::getStatus, "ACTIVE")
                // 平台默认行（tenant_id=0）排前面，租户覆盖行排后面，
                // 这样下面的"后者覆盖前者"才是正确的优先级方向
                .orderByAsc(DictDataPO::getTenantId)
                .orderByAsc(DictDataPO::getSort));

        return mergeByTenantPriority(rows);
    }

    @Override
    public Optional<DictDataDTO> findDataById(long id) {
        DictDataPO po = dictDataMapper.selectOne(new LambdaQueryWrapper<DictDataPO>()
                .eq(DictDataPO::getId, id)
                .in(DictDataPO::getTenantId, platformOrTenantScope()));
        return Optional.ofNullable(po).map(DictQueryPortImpl::toDataDTO);
    }

    // ==================================================================
    // 合并与工具
    // ==================================================================

    /**
     * 同一 {@code dictValue} 保留租户级，丢弃平台级。
     *
     * <h3>为什么用 LinkedHashMap 而不是先分组再挑</h3>
     * 依赖"平台行先、租户行后"的排序，顺序 put 即可让后者覆盖前者 ——
     * 一次遍历、语义直接。
     *
     * <p>若改成"按 tenantId 分组后优先取非 0 组"，就要处理
     * "租户只覆盖了部分项、其余仍用平台默认"这个真实场景，
     * 代码会明显变复杂，而收益只是省一次 put。
     */
    private List<DictDataDTO> mergeByTenantPriority(List<DictDataPO> rows) {
        Map<String, DictDataPO> byValue = new LinkedHashMap<>();
        for (DictDataPO row : rows) {
            byValue.put(row.getDictValue(), row);
        }
        List<DictDataDTO> merged = new ArrayList<>(byValue.size());
        byValue.values().forEach(po -> merged.add(toDataDTO(po)));
        // 合并后重新排序：覆盖会打乱原有的 sort 顺序
        merged.sort(Comparator.comparing(DictDataDTO::sort,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return merged;
    }

    /**
     * 字典的可见范围：平台默认 + 当前租户。
     *
     * <p>⚠️ 这是本类所有查询都必须带上的条件。抽成一个方法而不是内联，
     * 是为了让"漏写"变成一件显眼的事 —— 每个查询方法里都能看到它，
     * 评审时也只需确认它存在。
     */
    private List<Long> platformOrTenantScope() {
        long tenantId = TenantContext.require();
        // 超管/平台上下文（tenantId=0）只看平台默认，避免 IN (0,0) 这种冗余
        return tenantId == 0L ? List.of(0L) : List.of(0L, tenantId);
    }

    private static DictTypeDTO toTypeDTO(DictTypePO po) {
        return new DictTypeDTO(po.getId(), po.getDictName(), po.getDictType(),
                po.getStatus(), po.getRemark(), po.getTenantId(), po.getCreateTime());
    }

    private static DictDataDTO toDataDTO(DictDataPO po) {
        return new DictDataDTO(po.getId(), po.getDictType(), po.getDictLabel(),
                po.getDictValue(), po.getSort(), po.getCssClass(), po.getListClass(),
                po.getIsDefault(), po.getStatus(), po.getRemark(), po.getTenantId());
    }
}
