-- =====================================================================
-- V1.0.4 平台级种子数据：部门 / 角色 / 菜单树 / 字典 / 参数
--
-- 关于 tenant_id：
--   · 菜单树是**平台级共享**（tenant_id=0），所有租户共用同一套菜单定义，
--     租户间的可见范围差异由「角色-菜单分配」表达
--   · 角色 / 部门是**租户级**，这里为 V1.0.0 种下的演示租户（id=1）创建
--
-- 关于超级管理员账号：
--   ⚠️ 本脚本**刻意不插入 admin 用户**。原因：password 必须是 BCrypt 哈希，
--   而写死一个哈希字符串意味着"密码是多少"只能靠注释说明，一旦与实际不符
--   （或换人接手）就会陷入"密码明明是这个却登不上"的排查地狱。
--   改为由应用启动时的 AdminUserInitializer 用真实 BCrypt 计算并创建，
--   并在首次创建时把初始密码打印到启动日志。
--   这是"配置/数据里不出现不可验证的密文"原则的体现。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 演示租户的根部门
-- ancestors='0' 表示父级为平台根；子部门形如 '0,100'
-- ---------------------------------------------------------------------
INSERT INTO `org_dept` (`id`, `tenant_id`, `parent_id`, `ancestors`, `dept_name`, `sort`, `status`, `remark`)
VALUES (100, 1, 0, '0', '总公司', 1, 'ACTIVE', '初始化种子数据')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 角色
--   builtin=1 的角色不允许改标识、不允许删除（由 Role 聚合裁决）
--   data_scope 决定数据权限拼什么条件
-- ---------------------------------------------------------------------
INSERT INTO `iam_role` (`id`, `tenant_id`, `role_name`, `role_key`, `sort`, `data_scope`, `builtin`, `status`, `remark`)
VALUES
    (1, 1, '超级管理员', 'SUPER_ADMIN', 1, 'ALL', 1, 'ACTIVE', '内置角色：拥有全部权限与全部数据范围'),
    (2, 1, '普通员工',   'STAFF',       2, 'SELF', 0, 'ACTIVE', '示例角色：仅能查看本人数据')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 菜单树（tenant_id=0 平台级）
--
-- menu_type：DIR 目录 / MENU 菜单 / BUTTON 按钮
-- perms 仅在 BUTTON 上有值，格式 {context}:{resource}:{action}
-- component 存相对路径，前端用 import.meta.glob('@/views/**/*.vue') 查表映射，
--           因此**菜单与前端代码之间没有硬编码耦合**
-- ---------------------------------------------------------------------
INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    -- ===== 系统管理 =====
    (1,   0, 0, '系统管理', 'DIR',  '/system', NULL, NULL, 'Settings', 1, 1, 1, 'ACTIVE'),

    (100, 0, 1, '租户管理', 'MENU', 'tenant', 'iam/tenant/index', 'iam:tenant:query', 'Team', 1, 1, 1, 'ACTIVE'),
    (1001,0, 100, '查询租户', 'BUTTON', NULL, NULL, 'iam:tenant:query',  NULL, 1, 1, 1, 'ACTIVE'),
    (1002,0, 100, '新增租户', 'BUTTON', NULL, NULL, 'iam:tenant:create', NULL, 2, 1, 1, 'ACTIVE'),
    (1003,0, 100, '修改租户', 'BUTTON', NULL, NULL, 'iam:tenant:update', NULL, 3, 1, 1, 'ACTIVE'),
    (1004,0, 100, '关闭租户', 'BUTTON', NULL, NULL, 'iam:tenant:close',  NULL, 4, 1, 1, 'ACTIVE'),

    (200, 0, 1, '用户管理', 'MENU', 'user', 'iam/user/index', 'iam:user:query', 'User', 2, 1, 1, 'ACTIVE'),
    (2001,0, 200, '查询用户', 'BUTTON', NULL, NULL, 'iam:user:query',          NULL, 1, 1, 1, 'ACTIVE'),
    (2002,0, 200, '新增用户', 'BUTTON', NULL, NULL, 'iam:user:create',         NULL, 2, 1, 1, 'ACTIVE'),
    (2003,0, 200, '修改用户', 'BUTTON', NULL, NULL, 'iam:user:update',         NULL, 3, 1, 1, 'ACTIVE'),
    (2004,0, 200, '删除用户', 'BUTTON', NULL, NULL, 'iam:user:delete',         NULL, 4, 1, 1, 'ACTIVE'),
    (2005,0, 200, '重置密码', 'BUTTON', NULL, NULL, 'iam:user:reset-password', NULL, 5, 1, 1, 'ACTIVE'),
    (2006,0, 200, '导出用户', 'BUTTON', NULL, NULL, 'iam:user:export',         NULL, 6, 1, 1, 'ACTIVE'),

    (300, 0, 1, '角色管理', 'MENU', 'role', 'iam/role/index', 'iam:role:query', 'UserFilled', 3, 1, 1, 'ACTIVE'),
    (3001,0, 300, '查询角色', 'BUTTON', NULL, NULL, 'iam:role:query',  NULL, 1, 1, 1, 'ACTIVE'),
    (3002,0, 300, '新增角色', 'BUTTON', NULL, NULL, 'iam:role:create', NULL, 2, 1, 1, 'ACTIVE'),
    (3003,0, 300, '修改角色', 'BUTTON', NULL, NULL, 'iam:role:update', NULL, 3, 1, 1, 'ACTIVE'),
    (3004,0, 300, '删除角色', 'BUTTON', NULL, NULL, 'iam:role:delete', NULL, 4, 1, 1, 'ACTIVE'),
    (3005,0, 300, '分配权限', 'BUTTON', NULL, NULL, 'iam:role:assign', NULL, 5, 1, 1, 'ACTIVE'),

    (400, 0, 1, '菜单管理', 'MENU', 'menu', 'iam/menu/index', 'iam:menu:query', 'Menu', 4, 1, 1, 'ACTIVE'),
    (4001,0, 400, '查询菜单', 'BUTTON', NULL, NULL, 'iam:menu:query',  NULL, 1, 1, 1, 'ACTIVE'),
    (4002,0, 400, '新增菜单', 'BUTTON', NULL, NULL, 'iam:menu:create', NULL, 2, 1, 1, 'ACTIVE'),
    (4003,0, 400, '修改菜单', 'BUTTON', NULL, NULL, 'iam:menu:update', NULL, 3, 1, 1, 'ACTIVE'),
    (4004,0, 400, '删除菜单', 'BUTTON', NULL, NULL, 'iam:menu:delete', NULL, 4, 1, 1, 'ACTIVE'),

    (500, 0, 1, '部门管理', 'MENU', 'dept', 'org/dept/index', 'org:dept:query', 'OfficeBuilding', 5, 1, 1, 'ACTIVE'),
    (5001,0, 500, '查询部门', 'BUTTON', NULL, NULL, 'org:dept:query',  NULL, 1, 1, 1, 'ACTIVE'),
    (5002,0, 500, '新增部门', 'BUTTON', NULL, NULL, 'org:dept:create', NULL, 2, 1, 1, 'ACTIVE'),
    (5003,0, 500, '修改部门', 'BUTTON', NULL, NULL, 'org:dept:update', NULL, 3, 1, 1, 'ACTIVE'),
    (5004,0, 500, '删除部门', 'BUTTON', NULL, NULL, 'org:dept:delete', NULL, 4, 1, 1, 'ACTIVE'),

    (600, 0, 1, '岗位管理', 'MENU', 'post', 'org/post/index', 'org:post:query', 'Postcard', 6, 1, 1, 'ACTIVE'),
    (6001,0, 600, '查询岗位', 'BUTTON', NULL, NULL, 'org:post:query',  NULL, 1, 1, 1, 'ACTIVE'),
    (6002,0, 600, '新增岗位', 'BUTTON', NULL, NULL, 'org:post:create', NULL, 2, 1, 1, 'ACTIVE'),
    (6003,0, 600, '修改岗位', 'BUTTON', NULL, NULL, 'org:post:update', NULL, 3, 1, 1, 'ACTIVE'),
    (6004,0, 600, '删除岗位', 'BUTTON', NULL, NULL, 'org:post:delete', NULL, 4, 1, 1, 'ACTIVE'),

    -- ===== 平台管理 =====
    (2,   0, 0, '平台管理', 'DIR',  '/platform', NULL, NULL, 'SetUp', 2, 1, 1, 'ACTIVE'),

    (700, 0, 2, '字典管理', 'MENU', 'dict', 'platform/dict/index', 'plt:dict:query', 'Notebook', 1, 1, 1, 'ACTIVE'),
    (7001,0, 700, '查询字典', 'BUTTON', NULL, NULL, 'plt:dict:query',  NULL, 1, 1, 1, 'ACTIVE'),
    (7002,0, 700, '新增字典', 'BUTTON', NULL, NULL, 'plt:dict:create', NULL, 2, 1, 1, 'ACTIVE'),
    (7003,0, 700, '修改字典', 'BUTTON', NULL, NULL, 'plt:dict:update', NULL, 3, 1, 1, 'ACTIVE'),
    (7004,0, 700, '删除字典', 'BUTTON', NULL, NULL, 'plt:dict:delete', NULL, 4, 1, 1, 'ACTIVE'),

    (800, 0, 2, '参数配置', 'MENU', 'config', 'platform/config/index', 'plt:config:query', 'Tools', 2, 1, 1, 'ACTIVE'),
    (8001,0, 800, '查询参数', 'BUTTON', NULL, NULL, 'plt:config:query',  NULL, 1, 1, 1, 'ACTIVE'),
    (8002,0, 800, '新增参数', 'BUTTON', NULL, NULL, 'plt:config:create', NULL, 2, 1, 1, 'ACTIVE'),
    (8003,0, 800, '修改参数', 'BUTTON', NULL, NULL, 'plt:config:update', NULL, 3, 1, 1, 'ACTIVE'),
    (8004,0, 800, '删除参数', 'BUTTON', NULL, NULL, 'plt:config:delete', NULL, 4, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 角色-菜单：超管拥有全部菜单（用 SELECT 生成，避免手抄 40 个 ID 出错）
-- 注意携带 tenant_id（关联表也带租户列，见 V1.0.1 决策一）
-- ---------------------------------------------------------------------
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);

-- 普通员工：只给「用户管理」菜单与其查询按钮（演示权限收窄的效果）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 2, `id`
FROM `iam_menu`
WHERE `id` IN (1, 200, 2001)
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 2
                    AND rm.`menu_id` = `iam_menu`.`id`);

-- ---------------------------------------------------------------------
-- 字典（tenant_id=0 平台默认，租户可覆盖）
-- ---------------------------------------------------------------------
INSERT INTO `plt_dict_type` (`id`, `tenant_id`, `dict_name`, `dict_type`, `status`, `remark`)
VALUES (1, 0, '用户性别', 'sys_user_sex', 'ACTIVE', '系统内置'),
       (2, 0, '通用状态', 'sys_status', 'ACTIVE', '系统内置'),
       (3, 0, '用户状态', 'sys_user_status', 'ACTIVE', '系统内置'),
       (4, 0, '是否', 'sys_yes_no', 'ACTIVE', '系统内置'),
       (5, 0, '菜单类型', 'sys_menu_type', 'ACTIVE', '系统内置'),
       (6, 0, '数据范围', 'sys_data_scope', 'ACTIVE', '系统内置'),
       (7, 0, '租户状态', 'sys_tenant_status', 'ACTIVE', '系统内置')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

INSERT INTO `plt_dict_data` (`id`, `tenant_id`, `dict_type`, `dict_label`, `dict_value`, `sort`, `css_class`, `is_default`, `status`)
VALUES
    (101, 0, 'sys_user_sex', '男',   '0', 1, 'info',    1, 'ACTIVE'),
    (102, 0, 'sys_user_sex', '女',   '1', 2, 'danger',  0, 'ACTIVE'),
    (103, 0, 'sys_user_sex', '未知', '2', 3, 'default', 0, 'ACTIVE'),

    (201, 0, 'sys_status', '正常', 'ACTIVE',   1, 'success', 1, 'ACTIVE'),
    (202, 0, 'sys_status', '停用', 'DISABLED', 2, 'danger',  0, 'ACTIVE'),

    (301, 0, 'sys_user_status', '正常', 'ACTIVE',    1, 'success', 1, 'ACTIVE'),
    (302, 0, 'sys_user_status', '停用', 'SUSPENDED', 2, 'warning', 0, 'ACTIVE'),
    (303, 0, 'sys_user_status', '锁定', 'LOCKED',    3, 'danger',  0, 'ACTIVE'),

    (401, 0, 'sys_yes_no', '是', '1', 1, 'success', 0, 'ACTIVE'),
    (402, 0, 'sys_yes_no', '否', '0', 2, 'default', 1, 'ACTIVE'),

    (501, 0, 'sys_menu_type', '目录', 'DIR',    1, 'default', 0, 'ACTIVE'),
    (502, 0, 'sys_menu_type', '菜单', 'MENU',   2, 'info',    0, 'ACTIVE'),
    (503, 0, 'sys_menu_type', '按钮', 'BUTTON', 3, 'warning', 0, 'ACTIVE'),

    (601, 0, 'sys_data_scope', '全部数据',     'ALL',            1, 'danger',  0, 'ACTIVE'),
    (602, 0, 'sys_data_scope', '自定义数据',   'CUSTOM',         2, 'warning', 0, 'ACTIVE'),
    (603, 0, 'sys_data_scope', '本部门数据',   'DEPT',           3, 'info',    0, 'ACTIVE'),
    (604, 0, 'sys_data_scope', '本部门及以下', 'DEPT_AND_CHILD', 4, 'info',    0, 'ACTIVE'),
    (605, 0, 'sys_data_scope', '仅本人数据',   'SELF',           5, 'default', 1, 'ACTIVE'),

    (701, 0, 'sys_tenant_status', '待激活', 'PENDING',   1, 'warning', 0, 'ACTIVE'),
    (702, 0, 'sys_tenant_status', '正常',   'ACTIVE',    2, 'success', 0, 'ACTIVE'),
    (703, 0, 'sys_tenant_status', '已暂停', 'SUSPENDED', 3, 'warning', 0, 'ACTIVE'),
    (704, 0, 'sys_tenant_status', '已过期', 'EXPIRED',   4, 'danger',  0, 'ACTIVE'),
    (705, 0, 'sys_tenant_status', '已关闭', 'CLOSED',    5, 'default', 0, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 参数配置（tenant_id=0 平台默认，租户可覆盖同 key 的值）
-- ---------------------------------------------------------------------
INSERT INTO `plt_config` (`id`, `tenant_id`, `config_name`, `config_key`, `config_value`, `builtin`, `remark`)
VALUES (1, 0, '用户初始密码',   'sys.user.init-password', 'Admin@123456', 1, '新建用户与重置密码时使用的默认密码，首次登录强制修改'),
       (2, 0, '登录失败阈值',   'sys.login.max-fail',      '5',            1, '连续失败达到该次数后锁定账号'),
       (3, 0, '账号锁定时长(分)', 'sys.login.lock-minutes', '30',          1, '锁定时长，单位分钟'),
       (4, 0, '是否开启验证码', 'sys.captcha.enabled',     'false',        1, '开发期关闭，生产必须开启')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);
