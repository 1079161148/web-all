package com.webadmin.application.platform.port;

import java.util.Optional;

/**
 * 字典写端口。
 *
 * <p>字典是 L1 支撑域（无聚合），但应用层不能依赖 infrastructure，
 * 因此写能力以端口形式暴露。业务规则（重名校验、类型下仍有字典项时不可删）
 * 留在 {@code DictAppService}，端口只提供"读写能力"与"事实查询"。
 */
public interface DictWritePort {

    // ---- 字典类型 ----

    record TypeRow(Long id, Long tenantId, String dictName, String dictType,
                   String status, String remark) {
    }

    Optional<TypeRow> findType(long id);

    /** 同一租户下类型编码是否已存在。 */
    boolean typeExists(long tenantId, String dictType, Long excludeId);

    Long insertType(long tenantId, String dictName, String dictType, String status, String remark);

    void updateType(long id, String dictName, String dictType, String status, String remark);

    void deleteType(long id);

    /** 该类型下字典项数量（可见范围内：平台默认 + 本租户）。 */
    long countDataByType(String dictType);

    // ---- 字典项 ----

    record DataRow(Long id, Long tenantId, String dictType, String dictLabel,
                   String dictValue, Integer sort, String cssClass, String listClass,
                   Boolean isDefault, String status, String remark) {
    }

    Optional<DataRow> findData(long id);

    boolean dataExists(long tenantId, String dictType, String dictValue, Long excludeId);

    Long insertData(long tenantId, String dictType, String dictLabel, String dictValue,
                    Integer sort, String cssClass, String listClass, Boolean isDefault,
                    String status, String remark);

    void updateData(long id, String dictType, String dictLabel, String dictValue,
                    Integer sort, String cssClass, String listClass, Boolean isDefault,
                    String status, String remark);

    void deleteData(long id);
}
