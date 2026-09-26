-- =====================================================================
-- V1.1.3 审计日志 + 工单抢单（真实后端） + 菜单
--
-- ops_ticket.state 的状态机：PENDING → CLAIMED → DONE；
--                          PENDING → ESCALATED（SLA 超时惰性升级）
-- 抢单的原子性由条件 UPDATE 保证：
--   UPDATE ... SET state='CLAIMED' WHERE id=? AND state='PENDING'
-- affected=0 即"已被他人抢走" —— 不需要先查后改，数据库行锁天然串行化。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ops_ticket` (
    `id`          BIGINT       NOT NULL COMMENT '雪花/种子 ID',
    `tenant_id`   BIGINT       NOT NULL COMMENT '租户',
    `title`       VARCHAR(200) NOT NULL COMMENT '工单标题',
    `channel`     VARCHAR(16)  NOT NULL COMMENT '渠道：客服/运维/配送',
    `priority`    VARCHAR(16)  NOT NULL COMMENT 'P1/P2/P3',
    `state`       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/DONE/ESCALATED',
    `claimer`     VARCHAR(64)  NULL COMMENT '接单人（账号）',
    `sla_minutes` INT          NOT NULL COMMENT 'SLA 时限（分钟）',
    `created_at`  DATETIME(3)  NOT NULL COMMENT '创建时间（SLA 起点）',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_ops_ticket_tenant_state` (`tenant_id`, `state`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单调度演示';

INSERT INTO `ops_ticket` (`id`, `tenant_id`, `title`, `channel`, `priority`, `state`, `claimer`,
                          `sla_minutes`, `created_at`)
VALUES (9101, 1, '客户投诉：订单迟迟未发货，要求尽快响应', '客服', 'P1', 'PENDING', NULL, 15, NOW(3)),
       (9102, 1, '生产数据库主从延迟超过 30 秒', '运维', 'P1', 'PENDING', NULL, 15, NOW(3)),
       (9103, 1, '用户反馈发票抬头开错，需要重开', '客服', 'P2', 'PENDING', NULL, 30, NOW(3)),
       (9104, 1, '骑手失联 20 分钟，订单面临超时', '配送', 'P2', 'PENDING', NULL, 30, NOW(3)),
       (9105, 1, '测试环境磁盘水位 85%，申请扩容', '运维', 'P3', 'PENDING', NULL, 120, NOW(3)),
       (9106, 1, '咨询会员积分兑换规则', '客服', 'P3', 'PENDING', NULL, 120, NOW(3))
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 操作审计日志（字段级 diff 由领域事件处理器写入）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_audit_log` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`  BIGINT       NOT NULL,
    `user_id`    BIGINT       NOT NULL,
    `username`   VARCHAR(64)  NOT NULL,
    `action`     VARCHAR(32)  NOT NULL COMMENT 'CREATED/UPDATED/…',
    `biz_type`   VARCHAR(64)  NOT NULL COMMENT '业务类型（如 调研任务）',
    `biz_id`     VARCHAR(64)  NOT NULL,
    `summary`    VARCHAR(255) NOT NULL COMMENT '一句话摘要',
    `diff_text`  MEDIUMTEXT   NULL COMMENT '字段级变更（每行 字段: before → after）',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_audit_tenant_time` (`tenant_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作审计';

-- ---------------------------------------------------------------------
-- 菜单：操作审计 + 工单调度台补权限码
-- ---------------------------------------------------------------------
INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (5960, 0, 50, '操作审计', 'MENU', 'audit', 'showcase/audit/index',
     'tools:audit:read', 'DocumentTextOutline', 12, 1, 1, 'ACTIVE'),
    (5970, 0, 5400, '抢单', 'BUTTON', NULL, NULL, 'tools:dispatch:manage', NULL, 1, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- 工单调度台菜单补权限码（原有菜单 perms 为 NULL）
UPDATE `iam_menu`
SET `perms` = 'tools:dispatch:manage', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 5400 AND `perms` IS NULL;

-- 超管拥有全部菜单（与前序迁移保持同一写法）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);
