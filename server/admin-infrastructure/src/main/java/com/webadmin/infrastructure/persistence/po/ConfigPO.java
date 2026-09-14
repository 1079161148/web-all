package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
 * 参数配置 PO。
 *
 * <p>⚠️ {@code plt_config} 在租户拦截器忽略名单里（需要两级 fallback），
 * 因此查询层必须显式带 {@code tenant_id IN (0, ?)}。语义与字典完全相同，
 * 详见 {@link DictTypePO} 的类注释。
 */
@Getter
@Setter
@TableName("plt_config")
public class ConfigPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 0=平台级默认；其余为租户级覆盖。 */
    private Long tenantId;

    private String configName;
    private String configKey;
    private String configValue;

    /** 系统内置参数不允许删除 —— 删掉它可能让某个功能失去取值来源。 */
    private Boolean builtin;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
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
