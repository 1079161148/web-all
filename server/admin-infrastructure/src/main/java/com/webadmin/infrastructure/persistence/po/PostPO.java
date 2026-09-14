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

/** 岗位 PO。 */
@Getter
@Setter
@TableName("org_post")
public class PostPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户隔离由拦截器自动施加（org_post 不在忽略名单里）。 */
    private Long tenantId;

    private String postCode;
    private String postName;
    private Integer sort;
    private String status;

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
