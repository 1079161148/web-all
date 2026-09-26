package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单（调度演示）。 */
@Data
@TableName("ops_ticket")
public class OpsTicketPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String title;

    private String channel;

    private String priority;

    /** PENDING / CLAIMED / DONE / ESCALATED */
    private String state;

    private String claimer;

    private Integer slaMinutes;

    private LocalDateTime createdAt;

    private LocalDateTime updateTime;
}
