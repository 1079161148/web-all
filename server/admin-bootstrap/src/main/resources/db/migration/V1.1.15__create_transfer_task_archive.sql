-- 数据传输任务档案表。
--
-- <h3>为什么这张表是「档案」而不是「热状态」</h3>
-- 任务的工作数据（校验行、错误明细、结果文件字节）只在内存里，生命周期
-- 分钟级 —— 表里存的是**元数据快照**（状态/进度/计数/错误摘要），
-- 服务三个用途：
--   1. 重启后任务可查（get() 回落查表，非终态行按"重启中断"翻译为 FAILED）
--   2. 历史任务审计（谁在什么时候导了什么、结果如何）
--   3. 定期清理（7 天前的档案按时间删除，非终态的残留行同样按时间清）
--
-- 注意：重启后表中残留的非终态行**不会再被更新为终态**（工作线程已消失），
-- 读取层负责把它们翻译为 FAILED；清理层不区分终态与否，按 create_time 删。

CREATE TABLE IF NOT EXISTS `tool_transfer_task`
(
    `id`            VARCHAR(48)   NOT NULL COMMENT '任务ID（IMP-/EXP- 前缀 + 36 进制时间戳）',
    `tenant_id`     BIGINT        NOT NULL DEFAULT 0 COMMENT '租户ID',
    `dept_id`       BIGINT        NULL COMMENT '部门ID（导入行的默认归属，请求线程捕获固化）',
    `kind`          VARCHAR(16)   NOT NULL COMMENT '任务类型：IMPORT / EXPORT',
    `status`        VARCHAR(16)   NOT NULL COMMENT 'QUEUED/PARSING/VALIDATING/VALIDATED/IMPORTING/DONE/PARTIAL/FAILED/CANCELLED',
    `phase`         VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '当前阶段可读描述（轮询界面直接展示）',
    `file_name`     VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '源文件名（导出任务为固定名）',
    `progress`      INT           NOT NULL DEFAULT 0 COMMENT '进度 0~100',
    `total_rows`    INT           NOT NULL DEFAULT 0 COMMENT '总行数',
    `success_rows`  INT           NOT NULL DEFAULT 0 COMMENT '成功行数',
    `failed_rows`   INT           NOT NULL DEFAULT 0 COMMENT '失败行数',
    `error_summary` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '终态错误摘要（前几条拼接；完整明细不落库）',
    `create_by`     BIGINT        NULL COMMENT '创建人',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT        NULL COMMENT '更新人',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`      BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id',
    `version`       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    KEY `idx_tool_task_tenant` (`tenant_id`, `create_time`),
    KEY `idx_tool_task_status` (`status`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='数据传输任务档案（导入/导出，重启后可查）';
