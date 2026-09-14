package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.webadmin.application.platform.port.DictWritePort;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.infrastructure.persistence.mapper.DictDataMapper;
import com.webadmin.infrastructure.persistence.mapper.DictTypeMapper;
import com.webadmin.infrastructure.persistence.po.DictDataPO;
import com.webadmin.infrastructure.persistence.po.DictTypePO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 字典写端口实现。 */
@Repository
@RequiredArgsConstructor
public class DictWritePortImpl implements DictWritePort {

    private final DictTypeMapper dictTypeMapper;
    private final DictDataMapper dictDataMapper;

    // ==================================================================
    // 字典类型
    // ==================================================================

    @Override
    public Optional<TypeRow> findType(long id) {
        DictTypePO po = dictTypeMapper.selectOne(new LambdaQueryWrapper<DictTypePO>()
                .eq(DictTypePO::getId, id).in(DictTypePO::getTenantId, scope()));
        return Optional.ofNullable(po).map(p -> new TypeRow(p.getId(), p.getTenantId(),
                p.getDictName(), p.getDictType(), p.getStatus(), p.getRemark()));
    }

    @Override
    public boolean typeExists(long tenantId, String dictType, Long excludeId) {
        return dictTypeMapper.exists(new LambdaQueryWrapper<DictTypePO>()
                .eq(DictTypePO::getTenantId, tenantId)
                .eq(DictTypePO::getDictType, dictType)
                .ne(excludeId != null, DictTypePO::getId, excludeId));
    }

    @Override
    public Long insertType(long tenantId, String dictName, String dictType,
                           String status, String remark) {
        DictTypePO po = new DictTypePO();
        po.setId(IdWorker.getId());
        // ⚠️ tenant_id 必须显式设置：本表在租户拦截器忽略名单里，
        // 拦截器**不会**自动补 tenant_id。忘了设置 → 落库为 0 → 变成"平台默认"，
        // 而写入者以为自己改的是本租户的字典。这是一个静默的语义错误。
        po.setTenantId(tenantId);
        po.setDictName(dictName);
        po.setDictType(dictType);
        po.setStatus(status == null ? "ACTIVE" : status);
        po.setRemark(remark);
        dictTypeMapper.insert(po);
        return po.getId();
    }

    @Override
    public void updateType(long id, String dictName, String dictType,
                           String status, String remark) {
        DictTypePO po = new DictTypePO();
        po.setId(id);
        po.setDictName(dictName);
        po.setDictType(dictType);
        po.setStatus(status);
        po.setRemark(remark);
        // tenant_id 不在更新列中：类型归属租户不应被改写，
        // 否则一次编辑就能把"租户字典"变成"平台默认"，影响所有租户
        dictTypeMapper.updateById(po);
    }

    @Override
    public void deleteType(long id) {
        dictTypeMapper.deleteById(id);
    }

    @Override
    public long countDataByType(String dictType) {
        Long count = dictDataMapper.selectCount(new LambdaQueryWrapper<DictDataPO>()
                .in(DictDataPO::getTenantId, scope())
                .eq(DictDataPO::getDictType, dictType));
        return count == null ? 0L : count;
    }

    // ==================================================================
    // 字典项
    // ==================================================================

    @Override
    public Optional<DataRow> findData(long id) {
        DictDataPO po = dictDataMapper.selectOne(new LambdaQueryWrapper<DictDataPO>()
                .eq(DictDataPO::getId, id).in(DictDataPO::getTenantId, scope()));
        return Optional.ofNullable(po).map(this::toRow);
    }

    @Override
    public boolean dataExists(long tenantId, String dictType, String dictValue, Long excludeId) {
        return dictDataMapper.exists(new LambdaQueryWrapper<DictDataPO>()
                .eq(DictDataPO::getTenantId, tenantId)
                .eq(DictDataPO::getDictType, dictType)
                .eq(DictDataPO::getDictValue, dictValue)
                .ne(excludeId != null, DictDataPO::getId, excludeId));
    }

    @Override
    public Long insertData(long tenantId, String dictType, String dictLabel, String dictValue,
                           Integer sort, String cssClass, String listClass, Boolean isDefault,
                           String status, String remark) {
        DictDataPO po = new DictDataPO();
        po.setId(IdWorker.getId());
        po.setTenantId(tenantId);
        po.setDictType(dictType);
        po.setDictLabel(dictLabel);
        po.setDictValue(dictValue);
        po.setSort(sort == null ? 0 : sort);
        po.setCssClass(cssClass);
        po.setListClass(listClass);
        po.setIsDefault(isDefault != null && isDefault);
        po.setStatus(status == null ? "ACTIVE" : status);
        po.setRemark(remark);
        dictDataMapper.insert(po);
        return po.getId();
    }

    @Override
    public void updateData(long id, String dictType, String dictLabel, String dictValue,
                           Integer sort, String cssClass, String listClass, Boolean isDefault,
                           String status, String remark) {
        DictDataPO po = new DictDataPO();
        po.setId(id);
        po.setDictType(dictType);
        po.setDictLabel(dictLabel);
        po.setDictValue(dictValue);
        po.setSort(sort);
        po.setCssClass(cssClass);
        po.setListClass(listClass);
        po.setIsDefault(isDefault);
        po.setStatus(status);
        po.setRemark(remark);
        dictDataMapper.updateById(po);
    }

    @Override
    public void deleteData(long id) {
        dictDataMapper.deleteById(id);
    }

    // ==================================================================

    private DataRow toRow(DictDataPO p) {
        return new DataRow(p.getId(), p.getTenantId(), p.getDictType(), p.getDictLabel(),
                p.getDictValue(), p.getSort(), p.getCssClass(), p.getListClass(),
                p.getIsDefault(), p.getStatus(), p.getRemark());
    }

    /** 同上：字典表不在拦截器覆盖范围内，可见范围必须显式表达。 */
    private List<Long> scope() {
        long tenantId = TenantContext.require();
        return tenantId == 0L ? List.of(0L) : List.of(0L, tenantId);
    }
}
