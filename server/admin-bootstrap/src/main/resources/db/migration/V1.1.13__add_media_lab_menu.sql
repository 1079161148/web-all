-- =====================================================================
-- V1.1.13 「图片视频处理」菜单（技术方案演示子页）
--
-- 与 V1.1.11「技术专题」同类：挂在 parent_id = 50（业务/技术方案演示分组）。
--
-- <h3>为什么这是交互式页面而不是内容页</h3>
-- 图片处理的坑集中在三类边界：像素内存（大图解码）、编码有损性
-- （格式与参数语义）、浏览器能力（canvas 上限、跨域、格式支持度）。
-- 这三类都无法从代码看出对错，必须能亲手传一张真实大图、
-- 看着体积与尺寸怎么变才建立得起直觉 —— 所以做成可操作的处理台，
-- 技术边界说明作为同页的下半部分（复用 Topic 内容模型）。
--
-- perms 为 NULL：与 monitor / big-chart / 技术专题 保持一致，
-- 这类演示页靠菜单可见性控权。
--
-- MENU 的 path 为不带斜杠的相对片段，component 为 views 下的相对路径
-- （前端 import.meta.glob 查表映射）。
--
-- icon 只可用 @vicons/ionicons5 的导出名（V1.1.10 踩过），Images 已核对存在。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (5993, 0, 50, '图片视频处理', 'MENU', 'media-lab', 'showcase/media-lab/index',
     NULL, 'Images', 23, 1, 1, 'ACTIVE')
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
