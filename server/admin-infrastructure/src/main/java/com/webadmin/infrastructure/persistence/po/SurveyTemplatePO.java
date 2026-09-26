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

/** 调研模板 PO（{@code content} 为富文本 HTML，列表查询会显式排除该列）。 */
@Getter
@Setter
@TableName("srvy_template")
public class SurveyTemplatePO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String templateCode;
    private String templateName;
    private String category;
    private String content;
    private Integer usageCount;
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
