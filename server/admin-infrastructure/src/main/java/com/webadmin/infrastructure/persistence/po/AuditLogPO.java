package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;

/** 操作审计日志。 */
@Data
@TableName("sys_audit_log")
public class AuditLogPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    private String username;

    private String action;

    private String bizType;

    private String bizId;

    private String summary;

    private String diffText;

    private Instant createTime;

    public static AuditLogPO of(long tenantId, long userId, String username, String action,
                                String bizType, String bizId, String summary, String diffText) {
        AuditLogPO po = new AuditLogPO();
        po.setTenantId(tenantId);
        po.setUserId(userId);
        po.setUsername(username);
        po.setAction(action);
        po.setBizType(bizType);
        po.setBizId(bizId);
        po.setSummary(summary);
        po.setDiffText(diffText);
        po.setCreateTime(Instant.now());
        return po;
    }

    public static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
