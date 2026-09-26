-- =====================================================================
-- 消息中心：站内信表 + 发布权限
--
-- 设计取舍（一表多行 = 按接收者扇出）：
--   公告是"发给全租户"的，有两种存法：
--   a) 一条公告 + 一张"谁读过"的关联表（读时 JOIN 判断未读）
--   b) 发送时按接收者扇出，一人一行（本实现）
--   选 (b) 的理由：未读数/已读状态是<b>每行一个布尔列</b>，
--   查询与更新都是单表（未读数 = COUNT WHERE is_read=0），
--   不需要"公告 × 用户"的笛卡尔推断；代价是存储随人数放大 ——
--   单租户用户量在千级以内（套餐上限），完全可接受。
--   关键索引 (tenant_id, receiver_id, is_read, del_flag) 覆盖热路径：
--   未读角标是每次打开页面都会查的查询。
--
-- msg_type 的用途：前端按类型区分图标/文案（公告 / 提醒 / 系统）。
--
-- 权限点挂在「平台管理」目录(2)下：发布公告是平台运营动作。
-- 超管对权限码有豁免（CachedPermissions.has 对超管直接返回 true），
-- 因此这里不再重复 V1.0.4 的"超管拥有全部菜单"逻辑 —— 不对，
-- 菜单树（前端可见性）依赖 role_menu 记录，超管例外由菜单接口处理
-- （AuthAppService.currentUserMenus 对超管返回全部），无需关联表。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `plt_message`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `tenant_id`   BIGINT       NOT NULL COMMENT '租户ID',
    `receiver_id` BIGINT       NOT NULL COMMENT '接收者用户 ID（发送时按接收者扇出，一人一行）',
    `msg_type`    VARCHAR(16)  NOT NULL DEFAULT 'NOTICE' COMMENT '类型：NOTICE 公告 / REMIND 提醒 / SYSTEM 系统',
    `title`       VARCHAR(128) NOT NULL COMMENT '标题',
    `content`     TEXT         NULL COMMENT '正文（富文本场景预留；纯文本也存这里）',
    `sender_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '发送者用户 ID（0 = 系统）',
    `is_read`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0 未读 / 1 已读',
    `read_time`   DATETIME(3)  NULL COMMENT '阅读时间',
    `create_by`   BIGINT       NULL,
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL,
    `update_time` DATETIME(3)  NULL,
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    KEY `idx_plt_message_inbox` (`tenant_id`, `receiver_id`, `is_read`, `del_flag`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='站内信（按接收者扇出，一人一行）';

-- ---------------------------------------------------------------------
-- 权限点：发布公告
-- ---------------------------------------------------------------------
INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (8501, 0, 2, '发布公告', 'BUTTON', NULL, NULL, 'plt:message:publish', NULL, 9, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- 超管拥有全部菜单（沿用既有写法；超管的权限码判定另有豁免，这里保菜单树可见性）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);
