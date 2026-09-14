package com.webadmin.application.platform;

import com.webadmin.application.platform.port.DictWritePort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典应用服务（L1 支撑域，事务脚本）。
 *
 * <h3>写入落在「当前租户」，而不是平台默认</h3>
 * 字典表支持「平台默认(tenant_id=0) + 租户覆盖」。
 * 本服务的所有写入都落在<b>当前登录用户所在租户</b> ——
 * 也就是说，租户管理员编辑字典时产生的是<b>本租户的覆盖行</b>，
 * 平台默认行不受影响。平台默认值来自迁移脚本，改它需要平台级上下文（tenant=0）。
 *
 * <p>这个规则必须显式：若把编辑写成"更新 tenant_id=0 的那一行"，
 * 一次租户侧的操作就会<b>改掉所有租户看到的默认值</b> ——
 * 而操作者只是想调自己这边的一个显示文案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictAppService {

    private final DictWritePort dictWritePort;

    // ==================================================================
    // 字典类型
    // ==================================================================

    @Transactional
    public Long createType(String dictName, String dictType, String status, String remark) {
        long tenantId = TenantContext.require();
        if (dictWritePort.typeExists(tenantId, dictType, null)) {
            throw new BizException(IamErrorCode.DICT_TYPE_DUPLICATED,
                    "字典类型「" + dictType + "」已存在");
        }
        Long id = dictWritePort.insertType(tenantId, dictName, dictType, status, remark);
        log.info("创建字典类型 tenantId={} dictType={} id={}", tenantId, dictType, id);
        return id;
    }

    @Transactional
    public void updateType(long id, String dictName, String dictType,
                           String status, String remark) {
        DictWritePort.TypeRow existing = dictWritePort.findType(id)
                .orElseThrow(() -> new BizException(IamErrorCode.DICT_TYPE_NOT_FOUND,
                        "字典类型不存在"));

        if (!existing.dictType().equals(dictType)) {
            // 改编码会连带影响：已成历史存储的 dictValue 不会变，
            // 但字典项与类型的关联会断掉（字典项靠 dictType 关联而非外键）。
            // 因此这里既做重名检查，也把风险说清楚。
            if (dictWritePort.typeExists(existing.tenantId(), dictType, id)) {
                throw new BizException(IamErrorCode.DICT_TYPE_DUPLICATED,
                        "字典类型「" + dictType + "」已存在");
            }
            long used = dictWritePort.countDataByType(existing.dictType());
            log.warn("修改字典类型编码 dictType: {} → {}，该类型下已有 {} 个字典项，"
                            + "它们的 dictType 字段不会自动跟随，需一并修改",
                    existing.dictType(), dictType, used);
        }

        dictWritePort.updateType(id, dictName, dictType, status, remark);
    }

    @Transactional
    public void deleteType(long id) {
        DictWritePort.TypeRow existing = dictWritePort.findType(id)
                .orElseThrow(() -> new BizException(IamErrorCode.DICT_TYPE_NOT_FOUND,
                        "字典类型不存在"));
        long dataCount = dictWritePort.countDataByType(existing.dictType());
        if (dataCount > 0) {
            throw new BizException(IamErrorCode.DICT_TYPE_HAS_DATA,
                    "该字典类型下还有 " + dataCount + " 个字典项，请先删除它们"
                            + "（字典项以 dictType 字符串关联，不做级联删除）");
        }
        dictWritePort.deleteType(id);
        log.info("删除字典类型 id={} dictType={}", id, existing.dictType());
    }

    // ==================================================================
    // 字典项
    // ==================================================================

    @Transactional
    public Long createData(String dictType, String dictLabel, String dictValue, Integer sort,
                           String cssClass, String listClass, Boolean isDefault,
                           String status, String remark) {
        long tenantId = TenantContext.require();
        if (dictWritePort.dataExists(tenantId, dictType, dictValue, null)) {
            throw new BizException(IamErrorCode.DICT_DATA_DUPLICATED,
                    "该字典类型下已存在键值为「" + dictValue + "」的字典项");
        }
        Long id = dictWritePort.insertData(tenantId, dictType, dictLabel, dictValue, sort,
                cssClass, listClass, isDefault, status, remark);
        log.info("创建字典项 tenantId={} dictType={} value={}", tenantId, dictType, dictValue);
        return id;
    }

    @Transactional
    public void updateData(long id, String dictType, String dictLabel, String dictValue,
                           Integer sort, String cssClass, String listClass, Boolean isDefault,
                           String status, String remark) {
        DictWritePort.DataRow existing = dictWritePort.findData(id)
                .orElseThrow(() -> new BizException(IamErrorCode.DICT_DATA_NOT_FOUND,
                        "字典项不存在"));
        if (dictWritePort.dataExists(existing.tenantId(), dictType, dictValue, id)) {
            throw new BizException(IamErrorCode.DICT_DATA_DUPLICATED,
                    "该字典类型下已存在键值为「" + dictValue + "」的字典项");
        }
        dictWritePort.updateData(id, dictType, dictLabel, dictValue, sort,
                cssClass, listClass, isDefault, status, remark);
    }

    @Transactional
    public void deleteData(long id) {
        dictWritePort.findData(id).orElseThrow(() -> new BizException(
                IamErrorCode.DICT_DATA_NOT_FOUND, "字典项不存在"));
        dictWritePort.deleteData(id);
        log.info("删除字典项 id={}", id);
    }
}
