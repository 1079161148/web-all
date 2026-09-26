-- =====================================================================
-- V1.1.14 「流式布局大屏」菜单（技术方案演示子页）
--
-- 挂在 parent_id = 50（业务/技术方案演示分组），与 V1.1.11「技术专题」、
-- V1.1.13「图片视频处理」同类。
--
-- <h3>这个页面演示什么</h3>
-- ECharts 大屏的两种做法里，本页是「流式布局」方案（另一种是整体 transform
-- 缩放）：元素真的跟着容器重排，只有 CSS 管不到的 ECharts 内部配置
-- （fontSize/padding/symbolSize 等）才按容器比例换算，并通过 ResizeObserver
-- 盯<b>容器</b>（而非 window）触发重绘。
--
-- <h3>依赖的静态资源</h3>
-- 中国地图 GeoJSON 放在 apps/admin/public/map/china.json（569KB），
-- 由页面运行时 fetch —— 不打进 bundle，否则会顶穿体积门禁。
-- 部署时需确保该文件随前端产物一起发布。
--
-- perms 为 NULL：与 monitor / big-chart / 技术专题 保持一致（演示页靠菜单可见性控权）。
-- icon 只可用 @vicons/ionicons5 的导出名（V1.1.10 踩过），Desktop 已核对存在。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (5994, 0, 50, '流式布局大屏', 'MENU', 'screen-flow', 'showcase/screen-flow/index',
     NULL, 'Desktop', 24, 1, 0, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- keep_alive = 0：大屏页自带 1 秒时钟与 3 秒数据刷新定时器，
-- 被缓存后在后台仍会持续跑（且用户切回时看到的是"停住的时间"）。

-- 超管拥有全部菜单（与前序迁移保持同一写法）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);
