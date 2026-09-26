-- =====================================================================
-- V1.1.11 「直播技术专题」菜单（技术方案演示子页）
--
-- 与 V1.1.1「实时监控大屏」同类：挂在 parent_id = 50（业务/技术方案
-- 演示分组）。这类页面是**纯前端内容页**，不涉及后端接口，因此
-- perms 为 NULL（与 monitor / big-chart / observability 等保持一致）；
-- 权限控制靠"菜单可见性"本身完成。
--
-- MENU 的 path 是不带斜杠的相对片段，component 为 views 下的相对路径
-- （前端 import.meta.glob 查表映射，不硬编码路由）—— 与 V1.1.6/1.1.9
-- 同一约定。
--
-- icon 只可用 @vicons/ionicons5 的导出名（V1.1.10 踩过：写成别的
-- 图标库的名字会被降级为首字母字形）。Videocam 已核对存在。
--
-- keep_alive = 1：内容页无外部会话状态，缓存后切回更快。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (5992, 0, 50, '直播技术专题', 'MENU', 'live-tech', 'showcase/live-tech/index',
     NULL, 'Videocam', 22, 1, 1, 'ACTIVE')
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
