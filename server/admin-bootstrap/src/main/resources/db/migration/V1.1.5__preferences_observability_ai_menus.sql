-- =====================================================================
-- V1.1.5 前端偏好 / 可观测事件表 + 新菜单（打印中心 / 前端可观测 / AI 落地）
-- =====================================================================

-- 用户偏好（表格列布局等用户级 KV；键格式与值上限由应用层把关）
CREATE TABLE IF NOT EXISTS `sys_user_preference` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`  BIGINT       NOT NULL,
    `user_id`    BIGINT       NOT NULL,
    `pref_key`   VARCHAR(64)  NOT NULL,
    `pref_value` TEXT         NULL,
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pref` (`tenant_id`, `user_id`, `pref_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- 前端可观测事件（批量追加写；聚合口径见 FeEventMapper）
CREATE TABLE IF NOT EXISTS `fe_event` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `tenant_id`   BIGINT        NOT NULL,
    `user_id`     BIGINT        NULL,
    `type`        VARCHAR(16)   NOT NULL COMMENT 'api/route/error/vital',
    `name`        VARCHAR(120)  NOT NULL,
    `page`        VARCHAR(200)  NULL,
    `duration_ms` INT           NULL,
    `detail`      VARCHAR(500)  NULL,
    `created_at`  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_fe_tenant_type_time` (`tenant_id`, `type`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- 新菜单：打印中心 / 前端可观测（亮点演示下）
INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (5990, 0, 50, '打印中心', 'MENU', 'print-center', 'showcase/print-center/index',
     NULL, 'Document', 13, 1, 1, 'ACTIVE'),
    (5991, 0, 50, '前端可观测', 'MENU', 'observability', 'showcase/observability/index',
     'tools:observability:read', 'Speedometer', 14, 1, 1, 'ACTIVE'),
    -- AI 落地：独立一级目录（下面挂 AI 实战页面）
    (6000, 0, 0, 'AI 落地', 'DIR', 'ai', NULL,
     NULL, 'Sparkles', 60, 1, 1, 'ACTIVE'),
    (6001, 0, 6000, 'AI 客服工作台', 'MENU', 'ai/chat', 'showcase/ai-chat/index',
     'ai:chat:use', 'ChatDotRound', 1, 1, 1, 'ACTIVE'),
    (6002, 0, 6000, 'AI 落地方案', 'MENU', 'ai/overview', 'showcase/ai-overview/index',
     NULL, 'InfoFilled', 2, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- 超管拥有全部菜单（与前序迁移保持同一写法）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);
