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

/** 菜单持久化对象。 */
@Getter
@Setter
@TableName("iam_menu")
public class MenuPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;
    private Long parentId;
    private String menuName;
    private String menuType;
    private String path;
    private String component;
    private String perms;
    private String icon;
    private Integer sort;
    private Boolean visible;
    private Boolean keepAlive;
    private Boolean alwaysShow;
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
