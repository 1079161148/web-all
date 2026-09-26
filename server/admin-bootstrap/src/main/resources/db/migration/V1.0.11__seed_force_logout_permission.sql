-- =====================================================================
-- 「强制下线」权限点（② 会话治理的最后一环）
--
-- 为什么是独立权限点，而不是复用 iam:user:update / reset-password：
--   强制下线的风险特征与两者都不同 —— 它不修改任何数据，
--   但能让一个人的工作**立即中断**（所有设备全部退出）。
--   "能改资料"或"能重置密码"的人未必应该拥有"能让别人当场掉线"的能力；
--   反过来，运维值班常常只需要"踢人"而不需要改数据。风险不同，必须可分别授予。
--
-- 授权策略：给已拥有「重置密码」的角色一并补上。
--   理由：两者的安全等级最接近（都能立即阻断某账号的使用），
--   且改密后旧令牌本就会被吊销（见 resetPassword 的版本号提升）——
--   也就是说"能重置密码"事实上已经隐含了"能让人掉线"，
--   显式授予只是把既有事实写明，避免出现"权限表上看不到、实际上却生效"的暗权限。
--
-- 幂等性：菜单 ON DUPLICATE KEY；关联 NOT EXISTS（沿用 V1.0.4/V1.0.9 已验证写法）。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (2007, 0, 200, '强制下线', 'BUTTON', NULL, NULL, 'iam:user:force-logout', NULL, 7, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- 超管拥有全部菜单
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);

-- 已有「重置密码」的角色 → 补上「强制下线」
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT rm.`tenant_id`, rm.`role_id`, 2007
FROM `iam_role_menu` rm
WHERE rm.`menu_id` = 2005
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` exist
                  WHERE exist.`role_id` = rm.`role_id`
                    AND exist.`menu_id` = 2007);
