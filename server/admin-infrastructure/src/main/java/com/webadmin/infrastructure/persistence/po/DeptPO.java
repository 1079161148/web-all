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

/** 部门持久化对象。 */
@Getter
@Setter
@TableName("org_dept")
public class DeptPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;
    private Long parentId;
    /** 祖级物化路径，如 {@code 0,100,205}。数据权限 DEPT_AND_CHILD 全靠它做前缀匹配。 */
    private String ancestors;
    private String deptName;
    private Integer sort;
    private Long leaderUserId;
    private String phone;
    private String email;
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
