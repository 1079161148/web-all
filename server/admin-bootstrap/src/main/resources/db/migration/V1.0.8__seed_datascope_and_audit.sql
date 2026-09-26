-- =====================================================================
-- 行级数据权限（四维权限的第三维）可验证化的三件前置事
--
-- 背景：数据权限的<b>代码</b>早就齐了（注解 + 拦截器 + 部门树展开 +
-- 条件构造 + 失败收敛为"无匹配数据"），但它在真实库里"看不出来"——
-- 因为缺的都是数据与索引这类**不在代码里的东西**。本迁移补齐它们。
--
-- 三件事与各自的理由：
--
-- 【一】多级部门树
--   原来只有一个「总公司(100)」。而 DEPT（本部门）与
--   DEPT_AND_CHILD（本部门及以下）**在单层树上完全等价** ——
--   意味着这两种范围写错了也测不出来。必须造出层级。
--
-- 【二】数据权限条件所需的索引
--   拦截器给 SQL 追加的是 `dept_id IN (...)` 与 `create_by = ?`。
--   这两列此前<b>没有任何索引</b>（设计文档 §9.3 索引规范要求：
--   凡是会被"自动追加"进 WHERE 的列，都必须有走索引的路径）。
--   少了索引不会报错，只会让每个列表页随数据量线性变慢 ——
--   正是那种"上线半年后才被发现"的性能债。
--
-- 【三】create_by / dept_id 的归属回填
--   `SELF` 范围拼的是 `create_by = 当前用户`。历史种子数据该列为 NULL，
--   于是「仅本人」角色<b>看不到任何数据</b>——看起来像权限坏了，
--   实际是数据没有归属。dept_id 同理（DEPT 系列范围依赖它）。
--
-- 幂等性：
--   插入用 ON DUPLICATE KEY（同 V1.0.4 的写法）；
--   回填用 COALESCE 只补空值，重复执行不会覆盖人工调整过的归属。
--   索引不加 IF NOT EXISTS —— MySQL 不支持该语法，而 Flyway 保证本文件只执行一次。
--
-- ⚠️ 超管 ID 是运行时生成的雪花 ID，因此回填必须用子查询取，不能写死数字。
--    若库中尚无 admin（全新库先跑迁移、后跑 AdminUserInitializer），
--    子查询返回 NULL，此时回填为 0 表示"无归属"，下一次启动不会自动修正 ——
--    这类数据属于"系统预置"，本就不该归属到某个用户。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 【一】多级部门树：总公司(100) → 研发中心(200) / 市场部(300) → 前端组(210)
--   ancestors 是物化路径（设计文档 §9.2 / V1.0.2 头部说明）：
--   DEPT_AND_CHILD 靠它做 `ancestors LIKE '0,100,200,%'` 前缀匹配展开子树。
-- ---------------------------------------------------------------------
INSERT INTO `org_dept` (`id`, `tenant_id`, `parent_id`, `ancestors`, `dept_name`, `sort`, `status`, `remark`)
VALUES
    (200, 1, 100, '0,100',     '研发中心', 2, 'ACTIVE', '数据权限验证用：二级部门'),
    (300, 1, 100, '0,100',     '市场部',   3, 'ACTIVE', '数据权限验证用：二级部门'),
    (210, 1, 200, '0,100,200', '前端组',   1, 'ACTIVE', '数据权限验证用：三级部门（用于区分 DEPT 与 DEPT_AND_CHILD）')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 【二】数据权限条件所需的索引
--   命名沿用项目规范 idx_{表}_{列...}；列顺序遵循"等值在前、范围在后"：
--   tenant_id 与 del_flag 几乎恒定，dept_id / create_by 是过滤主体。
-- ---------------------------------------------------------------------
-- SRVY_TASK：DEPT / DEPT_AND_CHILD / CUSTOM 范围的过滤列
CREATE INDEX `idx_srvy_task_tenant_dept`
    ON `srvy_task` (`tenant_id`, `dept_id`, `del_flag`);

-- SRVY_TASK：SELF 范围的过滤列
CREATE INDEX `idx_srvy_task_tenant_create_by`
    ON `srvy_task` (`tenant_id`, `create_by`, `del_flag`);

-- IAM_USER：SELF 范围（用户列表已标注 @DataScope，此前只走了 dept 索引）
CREATE INDEX `idx_iam_user_tenant_create_by`
    ON `iam_user` (`tenant_id`, `create_by`, `del_flag`);

-- ---------------------------------------------------------------------
-- 【三】归属回填
--   ① iam_user：把历史用户的 create_by 归到超管（仅补空值）
--   ② srvy_task：create_by 补超管；dept_id 按任务编码分散到不同部门，
--      这样 DEPT（只看本部门）与 DEPT_AND_CHILD（含子部门）会得到
--      **不同的结果集** —— 否则两种范围看起来一模一样，等于没验证。
-- ---------------------------------------------------------------------
UPDATE `iam_user`
SET `create_by` = COALESCE(`create_by`,
        COALESCE((SELECT `id` FROM (SELECT `id` FROM `iam_user`
                                    WHERE `username` = 'admin' AND `tenant_id` = 1 LIMIT 1) AS admin_user), 0))
WHERE `create_by` IS NULL;

UPDATE `srvy_task`
SET `create_by` = COALESCE(`create_by`,
        COALESCE((SELECT u.`id` FROM `iam_user` u
                  WHERE u.`username` = 'admin' AND u.`tenant_id` = `srvy_task`.`tenant_id`
                  LIMIT 1), 0)),
    `update_by` = COALESCE(`update_by`,
        COALESCE((SELECT u.`id` FROM `iam_user` u
                  WHERE u.`username` = 'admin' AND u.`tenant_id` = `srvy_task`.`tenant_id`
                  LIMIT 1), 0)),
    `dept_id`   = COALESCE(`dept_id`,
        CASE `task_code`
            WHEN 'TASK-NPS-2026Q3'     THEN 200  -- 研发中心
            WHEN 'TASK-UX-ONBOARD'     THEN 300  -- 市场部
            WHEN 'TASK-CHURN-ANALYSIS' THEN 210  -- 前端组（研发中心的子部门）
            ELSE 200
        END);

-- ⚠️ 注意上面两处的差别：
--   · iam_user 的自引用子查询必须包一层派生表（`SELECT ... FROM (...)` AS admin_user），
--     因为 MySQL 不允许在 UPDATE 的目标表上直接做子查询 ——
--     报错信息是 "You can't specify target table for update in FROM clause"。
--   · srvy_task 查的是别的表，可以正常按 `srvy_task.tenant_id` 关联，
--     这样将来有第 2 个租户时回填的是"该租户自己的 admin"，而不是写死的 tenant=1。
