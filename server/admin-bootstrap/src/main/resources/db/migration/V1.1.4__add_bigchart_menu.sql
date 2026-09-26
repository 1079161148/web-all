-- =====================================================================
-- V1.1.4 「百万级图表」菜单（亮点演示子页）
-- ECharts 海量数据最佳实践：Worker 流水线 + LTTB 降采样 +
-- 缩放感知自适应精度（折线 ↔ 蜡烛自动切换）+ 三种画法对照实验
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (5980, 0, 50, '百万级图表', 'MENU', 'big-chart', 'showcase/big-chart/index',
     NULL, 'TrendChart', 12, 1, 1, 'ACTIVE')
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
