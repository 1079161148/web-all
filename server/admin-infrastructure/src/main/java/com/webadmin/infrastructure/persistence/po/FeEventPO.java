package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 前端可观测事件 PO（批量追加写，从不更新）。 */
@Getter
@Setter
@TableName("`fe_event`")
public class FeEventPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    /** api / route / error / vital */
    private String type;

    private String name;

    private String page;

    private Integer durationMs;

    private String detail;

    private LocalDateTime createdAt;
}
