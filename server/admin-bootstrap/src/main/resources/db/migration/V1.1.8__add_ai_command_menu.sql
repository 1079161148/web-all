-- =====================================================================
-- V1.1.8 「AI 指挥中心」菜单（Agent Tool Calling）
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (6102, 0, 6000, 'AI 指挥中心', 'MENU', 'command', 'showcase/ai-command/index',
     'ai:agent:use', 'MagicStick', 0, 1, 1, 'ACTIVE')
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