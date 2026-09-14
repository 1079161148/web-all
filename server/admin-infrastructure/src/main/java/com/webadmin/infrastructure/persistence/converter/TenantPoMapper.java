package com.webadmin.infrastructure.persistence.converter;

import com.webadmin.application.iam.dto.TenantDTO;
import com.webadmin.infrastructure.persistence.po.TenantPO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * PO → 读模型 映射器（MapStruct，编译期生成）。
 *
 * <p>这才是 MapStruct 的正确用法：字段平铺、一一对应的<b>机械映射</b>。
 * 编译期生成实现，没有反射开销，且字段名不一致时<b>构建失败</b>而不是运行时静默丢字段 ——
 * 这是相对 {@code BeanUtils.copyProperties} 的本质优势。
 *
 * <p>{@code unmappedTargetPolicy = ERROR}：目标对象有字段没被映射时直接编译失败，
 * 防止新增字段后忘记处理。
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface TenantPoMapper {

    /** PO → 读模型。字段同名同类型，MapStruct 自动映射。 */
    TenantDTO toDTO(TenantPO po);

    List<TenantDTO> toDTOList(List<TenantPO> pos);
}
