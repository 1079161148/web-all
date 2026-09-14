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

/** 字典数据 PO。租户隔离语义同 {@link DictTypePO}（两级 fallback）。 */
@Getter
@Setter
@TableName("plt_dict_data")
public class DictDataPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    /** 字典类型编码（冗余字段，避免与类型表关联查询）。 */
    private String dictType;

    private String dictLabel;
    private String dictValue;
    private Integer sort;

    /** 前端标签样式：success / warning / error / info / default。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cssClass;

    /** 表格回显样式。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String listClass;

    /** MyBatis 默认按 {@code name()} 存取；数据库是 TINYINT(1)，需转换器处理。 */
    private Boolean isDefault;

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
