package com.webadmin.interfaces.rest.tenant.assembler;

import com.webadmin.application.iam.dto.TenantDTO;
import com.webadmin.application.iam.query.TenantPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.interfaces.rest.tenant.request.TenantPageRequest;
import com.webadmin.interfaces.rest.tenant.response.TenantResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 租户装配器：应用层读模型 ↔ 对外契约。
 *
 * <p>为什么不直接用 MapStruct：本层转换包含「需要在此处集中的横切逻辑」——
 * 字段权限脱敏、枚举转展示标签、时间格式化等。这些逻辑用声明式映射表达反而更晦涩。
 * 对象数量多、映射机械的场景（如 PO ↔ 聚合根）才用 MapStruct。
 */
@Component
public class TenantAssembler {

    /** 读模型 → 对外契约。 */
    public TenantResponse toResponse(TenantDTO dto) {
        if (dto == null) {
            return null;
        }
        return new TenantResponse(
                dto.id(),
                dto.code(),
                dto.name(),
                dto.status(),
                dto.planCode(),
                dto.planName(),
                dto.remainingUsers(),
                dto.remainingStorageBytes(),
                dto.remainingApiCalls(),
                dto.expireTime(),
                dto.remark(),
                dto.createTime());
    }

    public List<TenantResponse> toResponseList(List<TenantDTO> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(this::toResponse).toList();
    }

    public PageResult<TenantResponse> toResponsePage(PageResult<TenantDTO> page) {
        return PageResult.of(
                toResponseList(page.records()),
                page.total(),
                page.page(),
                page.size());
    }

    /** 接口层请求 → 应用层查询对象。 */
    public TenantPageQuery toQuery(TenantPageRequest request) {
        TenantPageQuery query = new TenantPageQuery();
        query.setPage(request.getPage());
        query.setSize(request.getSize());
        query.setSortField(request.getSortField());
        query.setSortOrder(request.getSortOrder());
        query.setCode(request.getCode());
        query.setName(request.getName());
        query.setStatus(request.getStatus());
        query.setPlanCode(request.getPlanCode());
        query.setCreatedFrom(request.getCreatedFrom());
        query.setCreatedTo(request.getCreatedTo());
        return query;
    }
}
