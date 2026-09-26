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
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 调研任务 PO。
 *
 * <p>{@code srvy_task} 是普通业务表（不在租户拦截器忽略名单里），
 * 因此 {@code tenant_id} 由拦截器自动施加与填充，这里不手写条件。
 */
@Getter
@Setter
@TableName("srvy_task")
public class SurveyTaskPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String taskCode;
    private String taskName;
    private String taskType;
    private String priority;
    private String ownerName;
    private Long deptId;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer progress;
    private String status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

    @TableLogic
    private Long delFlag;

    @Version
    private Integer version;
}
