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

/** 数据采集 PO。 */
@Getter
@Setter
@TableName("srvy_collect")
public class SurveyCollectPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long taskId;

    /** 任务名快照（写时由应用层填入，见 SurveyCollectAppService 的说明）。 */
    private String taskName;

    private String channel;
    private String collector;
    private LocalDate collectDate;
    private Integer sampleCount;
    private Integer validCount;
    private Integer qualityScore;
    private Long fileId;
    private String fileName;
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
