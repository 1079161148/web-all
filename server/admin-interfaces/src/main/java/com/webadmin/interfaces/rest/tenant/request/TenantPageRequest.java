package com.webadmin.interfaces.rest.tenant.request;

import com.webadmin.common.api.PageQuery;
import com.webadmin.domain.iam.model.tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户分页查询请求。
 *
 * <p>本类的字段与 {@code @Schema} 说明会直接生成到 OpenAPI，
 * 进而生成前端的筛选表单类型（设计文档 §10.1）。
 */
@Getter
@Setter
@Schema(description = "租户分页查询条件")
public class TenantPageRequest extends PageQuery {

    @Schema(description = "租户编码（模糊匹配）", example = "acme")
    private String code;

    @Schema(description = "租户名称（模糊匹配）", example = "Acme")
    private String name;

    @Schema(description = "租户状态")
    private TenantStatus status;

    @Schema(description = "套餐编码", example = "PRO")
    private String planCode;

    @Schema(description = "创建时间起（UTC）")
    private Instant createdFrom;

    @Schema(description = "创建时间止（UTC）")
    private Instant createdTo;
}
