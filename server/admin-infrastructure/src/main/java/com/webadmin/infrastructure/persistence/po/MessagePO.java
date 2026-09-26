package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 站内信 PO（按接收者扇出，一人一行）。
 *
 * <p>表设计的取舍见 V1.0.12 迁移脚本的说明：
 * 未读状态是本行的一个布尔列，未读数就是一次带索引的 COUNT ——
 * 这是对"角标"这种高频读最友好的形状。
 */
@Getter
@Setter
@TableName("plt_message")
public class MessagePO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    /** 接收者用户 ID（本行的归属人）。 */
    private Long receiverId;

    /** 类型：NOTICE 公告 / REMIND 提醒 / SYSTEM 系统。 */
    private String msgType;

    private String title;

    private String content;

    /** 发送者用户 ID；0 表示系统。 */
    private Long senderId;

    private Integer isRead;

    private Instant readTime;

    private Long createBy;
    private Instant createTime;
    private Long updateBy;
    private Instant updateTime;

    @TableLogic
    private Long delFlag;

    @Version
    private Integer version;
}
