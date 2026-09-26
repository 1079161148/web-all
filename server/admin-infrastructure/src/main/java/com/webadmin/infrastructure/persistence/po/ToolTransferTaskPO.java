package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 数据传输任务档案（{@code tool_transfer_task}）。
 *
 * <p>主键是业务任务 id（IMP-/EXP- 前缀字符串）而非雪花数 —— 档案按
 * 任务幂等 upsert，任务 id 本身就是天然去重键。
 */
@Data
@TableName("tool_transfer_task")
public class ToolTransferTaskPO {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long tenantId;

    private Long deptId;

    /** IMPORT / EXPORT。 */
    private String kind;

    /** 见 TransferTask.Status。 */
    private String status;

    private String phase;

    private String fileName;

    private Integer progress;

    private Integer totalRows;

    private Integer successRows;

    private Integer failedRows;

    private String errorSummary;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Long delFlag;

    @Version
    private Integer version;
}
