-- =====================================================================
-- V1.1.7 补插 AI 落地的子菜单（V1.1.5 的 id 与岗位按钮冲突被静默跳过）
--
-- 教训：ON DUPLICATE KEY UPDATE 对"主键冲突"的处理是静默的 ——
-- V1.1.5 用 6001/6002 作 AI 菜单 id，而 V1.1.0 已把它们分给
-- 「岗位管理」的两个按钮（BUTTON），结果 AI 目录（6000，无冲突）
-- 插入成功、两个子菜单（6001/6002，冲突）被静默跳过，
-- 表现为"目录存在但永远 404"。改用确认空闲的 id 重新插入。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (6100, 0, 6000, 'AI 客服工作台', 'MENU', 'chat', 'showcase/ai-chat/index',
     'ai:chat:use', 'ChatDotRound', 1, 1, 1, 'ACTIVE'),
    (6101, 0, 6000, 'AI 落地方案', 'MENU', 'overview', 'showcase/ai-overview/index',
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
