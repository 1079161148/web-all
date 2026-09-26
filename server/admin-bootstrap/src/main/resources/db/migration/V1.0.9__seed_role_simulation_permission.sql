-- =====================================================================
-- 「数据权限预览」（权限模拟器）的权限点
--
-- 为什么需要一个独立权限点，而不是复用 iam:role:query：
--   预览会回答"某个角色能看到多少数据、范围到哪"——它虽然只返回条数，
--   但暴露的是**他人可见范围**这一敏感信息（例如"审计员看不到任何任务"
--   本身就说明数据归属有问题）。查询角色权限足以让人改角色，
--   不该顺带获得窥探可见范围的能力；两者风险不同，必须能分别授予。
--
-- 授权策略：给"已经拥有『分配权限』的角色"自动补上。
--   理由：这个功能是**配完数据范围后的自检工具**。只有能改数据范围的人需要它，
--   而漏给会让"范围配好了却无法验证"，正好落回本功能要解决的痛点。
--   超管另有豁免（CachedPermissions.has 对超管直接返回 true），
--   因此这里的补授是为了**非超管**的权限管理员。
--
-- 幂等性：菜单用 ON DUPLICATE KEY（同 V1.0.4/V1.0.7）；
--   关联表用 NOT EXISTS 判重（沿用 V1.0.4 已验证可用的写法）。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (3006, 0, 300, '数据权限预览', 'BUTTON', NULL, NULL, 'iam:role:simulate', NULL, 6, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- 超管拥有全部菜单（与 V1.0.4 / V1.0.7 保持同一写法，避免各写一套）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);

-- 已有「分配权限」的角色 → 补上「数据权限预览」
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT rm.`tenant_id`, rm.`role_id`, 3006
FROM `iam_role_menu` rm
WHERE rm.`menu_id` = 3005
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` exist
                  WHERE exist.`role_id` = rm.`role_id`
                    AND exist.`menu_id` = 3006);
