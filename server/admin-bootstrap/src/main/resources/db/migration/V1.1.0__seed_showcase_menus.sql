-- =====================================================================
-- V1.1.0 「亮点演示」菜单
--
-- 定位：一个集中展示"实际业务难点与技术亮点"的演示模块。
--   这些页面绝大多数是**纯前端**的真实实现（算法真实、可交互），
--   不依赖新的后端表 —— 它们的价值在于展示工程能力与边界处理，
--   而不是持久化数据。
--
-- 权限策略：演示菜单不挂 BUTTON 权限点（perms 为 NULL）——
--   它们不操作任何业务数据，用菜单可见性控制即可；
--   若某天某个演示要接真实后端，再补独立权限点（参照 V1.0.9 的做法）。
--
-- 幂等性：ON DUPLICATE KEY（同 V1.0.4/V1.0.7/V1.0.9）。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    -- ===== 亮点演示 =====
    (50, 0, 0, '亮点演示', 'DIR', '/showcase', NULL, NULL, 'Rocket', 9, 1, 1, 'ACTIVE'),

    (5000, 0, 50, '高级表单', 'MENU', 'advanced-form', 'showcase/form/index', NULL, 'DocumentText', 1, 1, 1, 'ACTIVE'),
    (5100, 0, 50, 'SKU 多规格', 'MENU', 'sku', 'showcase/sku/index', NULL, 'Grid', 2, 1, 1, 'ACTIVE'),
    (5200, 0, 50, '虚拟滚动日志', 'MENU', 'virtual-log', 'showcase/virtual-log/index', NULL, 'List', 3, 1, 1, 'ACTIVE'),
    (5300, 0, 50, '审批流设计器', 'MENU', 'workflow', 'showcase/workflow/index', NULL, 'GitNetwork', 4, 1, 1, 'ACTIVE'),
    (5400, 0, 50, '工单调度台', 'MENU', 'dispatch', 'showcase/dispatch/index', NULL, 'Pulse', 5, 1, 1, 'ACTIVE'),
    (5500, 0, 50, '订单拆单结算', 'MENU', 'order', 'showcase/order/index', NULL, 'Cart', 6, 1, 1, 'ACTIVE'),
    (5600, 0, 50, '大文件上传', 'MENU', 'upload', 'showcase/upload/index', NULL, 'CloudUpload', 7, 1, 1, 'ACTIVE'),
    (5700, 0, 50, '协同编辑', 'MENU', 'collab', 'showcase/collab/index', NULL, 'People', 8, 1, 1, 'ACTIVE'),
    (5800, 0, 50, '权限控制', 'MENU', 'permission-demo', 'showcase/permission/index', NULL, 'ShieldCheckmark', 9, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- 超管拥有全部菜单（与 V1.0.4 / V1.0.7 / V1.0.9 保持同一写法）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);
