-- ---------------------------------------------------------------------
-- 修正菜单图标名
--
-- 原种子数据里的图标名（Team / User / UserFilled / OfficeBuilding /
-- Postcard / SetUp / Notebook / Tools）来自 Element Plus 图标集，
-- 而本项目前端按设计文档统一使用 @vicons/ionicons5，名字对不上会导致
-- 侧栏菜单全部降级为首字形。本迁移把这些名字改回 ionicons5 中真实存在
-- 的组件名，且保持各菜单图标语义可区分。
-- ---------------------------------------------------------------------
UPDATE `iam_menu`
SET `icon` = CASE `id`
    WHEN 100 THEN 'Business'      -- 租户管理：组织/企业
    WHEN 200 THEN 'Person'        -- 用户管理
    WHEN 300 THEN 'ShieldCheckmark' -- 角色管理：权限
    WHEN 500 THEN 'GitNetwork'    -- 部门管理：组织架构树
    WHEN 600 THEN 'Briefcase'     -- 岗位管理
    WHEN 2   THEN 'Construct'     -- 平台管理
    WHEN 700 THEN 'Book'          -- 字典管理
    WHEN 800 THEN 'Options'       -- 参数配置
    ELSE `icon`
END
WHERE `id` IN (100, 200, 300, 500, 600, 2, 700, 800);
