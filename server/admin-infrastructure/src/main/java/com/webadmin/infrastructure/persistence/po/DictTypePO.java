package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 字典类型 PO。
 *
 * <p>⚠️ {@code plt_dict_type} 在租户拦截器的<b>忽略名单</b>里（见 {@code TenantLineHandlerImpl}），
 * 因为它需要「平台默认(tenant_id=0) + 租户覆盖」两级 fallback，
 * 而拦截器只会拼 {@code tenant_id = ?}、会把平台默认行过滤掉。
 *
 * <p>因此<b>责任从拦截器转移到了查询层</b>：任何人写本表的查询都必须显式带
 * {@code tenant_id IN (0, ?)}。只写 {@code tenant_id = ?} 不会报错，
 * 只会表现为"字典项莫名其妙变少了" —— 因为平台默认值全被过滤掉了。
 */
@Getter
@Setter
@TableName("plt_dict_type")
public class DictTypePO {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 0=平台级默认；其余为租户级覆盖。 */
    private Long tenantId;

    private String dictName;
    private String dictType;
    private String status;
    private String remark;

    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

    @TableLogic
    private Long delFlag;

    @Version
    private Integer version;
}
