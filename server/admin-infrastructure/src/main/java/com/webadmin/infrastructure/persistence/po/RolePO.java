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

/** 角色持久化对象。 */
@Getter
@Setter
@TableName("iam_role")
public class RolePO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;
    private String roleName;
    private String roleKey;
    private Integer sort;
    /** 存字符串而非枚举：数据范围是配置数据，用字符串列更利于后台直接改。 */
    private String dataScope;
    /** 字段级权限策略（JSON 字符串），P1 未使用注解实现，此列预留。 */
    private String fieldPolicy;
    private Boolean builtin;
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
